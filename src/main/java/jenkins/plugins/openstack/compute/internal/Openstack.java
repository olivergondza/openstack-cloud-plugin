/*
 * The MIT License
 *
 * Copyright (c) 2015 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package jenkins.plugins.openstack.compute.internal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.TreeMultimap;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.Util;
import hudson.remoting.Which;
import hudson.util.FormValidation;
import org.apache.commons.lang.ObjectUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.client.IOSClientBuilder;
import org.openstack4j.api.compute.ComputeFloatingIPService;
import org.openstack4j.api.compute.ServerService;
import org.openstack4j.api.exceptions.ClientResponseException;
import org.openstack4j.api.exceptions.ResponseException;
import org.openstack4j.model.common.ActionResponse;
import org.openstack4j.model.common.BasicResource;
import org.openstack4j.model.common.Identifier;
import org.openstack4j.model.compute.Address;
import org.openstack4j.model.compute.Fault;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.FloatingIP;
import org.openstack4j.model.compute.Keypair;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.identity.v2.Access;
import org.openstack4j.model.identity.v3.Token;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.network.NetFloatingIP;
import org.openstack4j.model.network.Network;
import org.openstack4j.model.storage.block.Volume;
import org.openstack4j.model.storage.block.VolumeSnapshot;
import org.openstack4j.openstack.OSFactory;

import hudson.util.Secret;
import jenkins.model.Jenkins;

/**
 * Encapsulate {@link OSClient}.
 *
 * It is needed to make sure the client is truly immutable and provide easy-to-mock abstraction for unittesting
 *
 * For server manipulation, this implementation provides metadata fingerprinting
 * to identify machines started via this plugin from given instance so it will not
 * manipulate servers it does not "own". In other words, pretends that there are no
 * other machines running in connected tenant except for those started using this class.
 *
 * @author ogondza
 */
@Restricted(NoExternalUse.class)
@ThreadSafe
public class Openstack {

    private static final Logger LOGGER = Logger.getLogger(Openstack.class.getName());
    public static final String FINGERPRINT_KEY = "jenkins-instance";

    // Store the OS session token so clients can be created from it per all threads using this.
    private final ClientProvider clientProvider;

    private Openstack(@Nonnull String endPointUrl, @Nonnull String identity, @Nonnull Secret credential, @CheckForNull String region) {
        // TODO refactor to split tenant:username everywhere including UI
        String[] id = identity.split(":", 3);
        String tenant = id.length > 0 ? id[0] : "";
        String username = id.length > 1 ? id[1] : "";
        String domain = id.length > 2 ? id[2] : "";
        final IOSClientBuilder<? extends OSClient, ?> builder;
        if (domain.equals("")) {
            //If domain is empty it is assumed that is being used API V2
            builder = OSFactory.builderV2().endpoint(endPointUrl)
                     .credentials(username, credential.getPlainText())
                     .tenantName(tenant);
        } else {
            //If not it is assumed that it is being used API V3
            Identifier iDomain = Identifier.byName(domain);
            Identifier project = Identifier.byName(tenant);
            builder = OSFactory.builderV3().endpoint(endPointUrl)
                     .credentials(username, credential.getPlainText(), iDomain)
                     .scopeToProject(project, iDomain);
        }
        OSClient client = builder
                .authenticate()
                .useRegion(region)
        ;

        clientProvider = ClientProvider.get(client);
        debug("Openstack client created for " + endPointUrl);
    }

    /*exposed for testing*/
    public Openstack(@Nonnull final OSClient client) {
        this.clientProvider = new ClientProvider<Void>(null) {
            @Override public @Nonnull OSClient get() {
                return client;
            }

            @Override public Date _getExpires() {
                return ClientProvider.get(client).getExpires();
            }
        };
    }

    /**
     * Date representing time until this instance is valid to be used.
     */
    public @Nonnull Date getExpires() {
        return clientProvider.getExpires();
    }

    public @Nonnull Collection<? extends Network> getSortedNetworks() {
        List<? extends Network> nets = clientProvider.get().networking().network().list();
        Collections.sort(nets, RESOURCE_COMPARATOR);
        return nets;
    }

    private static final Comparator<BasicResource> RESOURCE_COMPARATOR = new Comparator<BasicResource>() {
        @Override
        public int compare(BasicResource o1, BasicResource o2) {
            return ObjectUtils.compare(o1.getName(), o2.getName());
        }
    };

    /**
     * Finds all {@link Image}s.
     * 
     * @return A Map of collections of images, indexed by name (or id if the
     *         image has no name) in ascending order and, in the event of
     *         name-collisions, the images for a given name are sorted by
     *         creation date.
     */
    public @Nonnull Map<String, Collection<Image>> getImages() {
        final List<? extends Image> list = clientProvider.get().images().listAll();
        final TreeMultimap<String, Image> set = TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, IMAGE_DATE_COMPARATOR);
        for (Image o : list) {
            final String name = Util.fixNull(o.getName());
            final String nameOrId = name.isEmpty() ? o.getId() : name;
            set.put(nameOrId, o);
        }
        return set.asMap();
    }

    private static final Comparator<Image> IMAGE_DATE_COMPARATOR = new Comparator<Image>() {
        @Override
        public int compare(Image o1, Image o2) {
            int result;
            result = ObjectUtils.compare(o1.getUpdatedAt(), o2.getUpdatedAt());
            if (result != 0) return result;
            result = ObjectUtils.compare(o1.getCreatedAt(), o2.getCreatedAt());
            if (result != 0) return result;
            result = ObjectUtils.compare(o1.getId(), o1.getId());
            return result;
        }
    };

    /**
     * Finds all {@link VolumeSnapshot}s that are {@link Volume.Status#AVAILABLE}.
     * 
     * @return A Map of collections of {@link VolumeSnapshot}s, indexed by name
     *         (or id if the volume snapshot has no name) in ascending order
     *         and, in the event of name-collisions, the volume snapshots for a
     *         given name are sorted by creation date.
     */
    public @Nonnull Map<String, Collection<VolumeSnapshot>> getVolumeSnapshots() {
        final List<? extends VolumeSnapshot> list = clientProvider.get().blockStorage().snapshots().list();
        final TreeMultimap<String, VolumeSnapshot> set = TreeMultimap.create(String.CASE_INSENSITIVE_ORDER, VOLUMESNAPSHOT_DATE_COMPARATOR);
        for (VolumeSnapshot o : list) {
            if (o.getStatus() != Volume.Status.AVAILABLE) {
                continue;
            }
            final String name = Util.fixNull(o.getName());
            final String nameOrId = name.isEmpty() ? o.getId() : name;
            set.put(nameOrId, o);
        }
        return set.asMap();
    }

    private static final Comparator<VolumeSnapshot> VOLUMESNAPSHOT_DATE_COMPARATOR = new Comparator<VolumeSnapshot>() {
        @Override
        public int compare(VolumeSnapshot o1, VolumeSnapshot o2) {
            int result;
            result = ObjectUtils.compare(o1.getCreated(), o2.getCreated());
            if( result!=0 ) return result;
            result = ObjectUtils.compare(o1.getId(), o1.getId());
            return result;
        }
    };

    public @Nonnull Collection<? extends Flavor> getSortedFlavors() {
        List<? extends Flavor> flavors = clientProvider.get().compute().flavors().list();
        Collections.sort(flavors, FLAVOR_COMPARATOR);
        return flavors;
    }

    private Comparator<Flavor> FLAVOR_COMPARATOR = new Comparator<Flavor>() {
        @Override
        public int compare(Flavor o1, Flavor o2) {
            return ObjectUtils.compare(o1.getName(), o2.getName());
        }
    };

    public @Nonnull List<String> getSortedIpPools() {
        ComputeFloatingIPService ipService = getComputeFloatingIPService();
        if (ipService == null) return Collections.emptyList();

        List<String> names = new ArrayList<>(ipService.getPoolNames());
        Collections.sort(names);
        return names;
    }

    public @Nonnull
    List<? extends AvailabilityZone> getAvailabilityZones(){
        final List<? extends AvailabilityZone> zones = clientProvider.get().compute().zones().list();
        Collections.sort(zones, AVAILABILITY_ZONES_COMPARATOR);
        return zones;
    }

    private static final Comparator<AvailabilityZone> AVAILABILITY_ZONES_COMPARATOR = new Comparator<AvailabilityZone>() {
        @Override
        public int compare(AvailabilityZone o1, AvailabilityZone o2) {
            return ObjectUtils.compare(o1.getZoneName(), o2.getZoneName());
        }
    };

    /**
     * @return null when user is not authorized to use the endpoint which is a valid use-case.
     */
    private @CheckForNull ComputeFloatingIPService getComputeFloatingIPService() {
        try {
            return clientProvider.get().compute().floatingIps();
        } catch (ClientResponseException ex) {
            // https://github.com/jenkinsci/openstack-cloud-plugin/issues/128
            if (ex.getStatus() == 403) return null;
            throw ex;
        }
    }

    public @Nonnull List<Server> getRunningNodes() {
        List<Server> running = new ArrayList<>();

        // We need details to inspect state and metadata
        final boolean detailed = true;
        for (Server n: clientProvider.get().compute().servers().list(detailed)) {
            if (isOccupied(n) && isOurs(n)) {
                running.add(n);
            }
        }

        return running;
    }

    public List<String> getFreeFipIds() {
        ArrayList<String> free = new ArrayList<>();
        for (NetFloatingIP ip : clientProvider.get().networking().floatingip().list()) {
            if (ip.getFixedIpAddress() == null) {
                free.add(ip.getId());
            }
        }
        return free;
    }

    public @Nonnull List<String> getSortedKeyPairNames() {
        List<String> keyPairs = new ArrayList<>();
        for (Keypair kp : clientProvider.get().compute().keypairs().list()) {
            keyPairs.add(kp.getName());
        }
        return keyPairs;
    }

    /**
     * Finds the Id(s) of all active {@link Image}s with the given name or ID.
     * If we have found multiple {@link Image}s then they will be listed in
     * ascending date order (oldest first).
     * 
     * @param nameOrId The name or ID of zero or more images.
     * @return It may contain zero, one or multiple IDs.
     */
    public @Nonnull List<String> getImageIdsFor(String nameOrId) {
        final Collection<Image> sortedObjects = new TreeSet<>(IMAGE_DATE_COMPARATOR);
        final Map<String, String> query = new HashMap<>(2);
        query.put("name", nameOrId);
        query.put("status", "active");
        final List<? extends Image> findByName = clientProvider.get().images().listAll(query);
        sortedObjects.addAll(findByName);
        if (nameOrId.matches("[0-9a-f-]{36}")) {
            final Image findById = clientProvider.get().images().get(nameOrId);
            if (findById != null && findById.getStatus() == Image.Status.ACTIVE) {
                sortedObjects.add(findById);
            }
        }
        final List<String> ids = new ArrayList<>();
        for (Image i : sortedObjects) {
            ids.add(i.getId());
        }
        return ids;
    }

    /**
     * Finds the Id(s) of all available {@link VolumeSnapshot}s with the given name
     * or ID. If we have found multiple {@link VolumeSnapshot}s then they will
     * be listed in ascending date order (oldest first).
     * 
     * @param nameOrId
     *            The name or ID of zero or more images.
     * @return A non-null list. It may contain zero, one or multiple IDs.
     */
    public @Nonnull List<String> getVolumeSnapshotIdsFor(String nameOrId) {
        final Collection<VolumeSnapshot> sortedObjects = new TreeSet<>(VOLUMESNAPSHOT_DATE_COMPARATOR);
        // OpenStack block-storage/v3 API doesn't allow us to filter by name, so fetch all and search.
        final Map<String, Collection<VolumeSnapshot>> allVolumeSnapshots = getVolumeSnapshots();
        final Collection<VolumeSnapshot> findByName = allVolumeSnapshots.get(nameOrId);
        if (findByName != null) {
            sortedObjects.addAll(findByName);
        }
        if (nameOrId.matches("[0-9a-f-]{36}")) {
            final VolumeSnapshot findById = clientProvider.get().blockStorage().snapshots().get(nameOrId);
            if (findById != null && findById.getStatus() == Volume.Status.AVAILABLE) {
                sortedObjects.add(findById);
            }
        }
        final List<String> ids = new ArrayList<>();
        for (VolumeSnapshot i : sortedObjects) {
            ids.add(i.getId());
        }
        return ids;
    }

    /**
     * Sets the name and description of a {@link Volume}. These will be visible
     * if a user looks at volumes using the OpenStack command-line or WebUI.
     * 
     * @param volumeId
     *            The ID of the volume whose name and description are to be set.
     * @param newVolumeName
     *            The new name for the volume.
     * @param newVolumeDescription
     *            The new description for the volume.
     */
    public void setVolumeNameAndDescription(String volumeId, String newVolumeName, String newVolumeDescription) {
        final ActionResponse res = clientProvider.get().blockStorage().volumes().update(volumeId, newVolumeName,
                newVolumeDescription);
        throwIfFailed(res);
    }

    /**
     * Determine whether the server is considered occupied by openstack plugin.
     */
    private static boolean isOccupied(@Nonnull Server server) {
        switch (server.getStatus()) {
            case UNKNOWN:
            case MIGRATING:
            case SHUTOFF:
            case DELETED:
                return false;
            case UNRECOGNIZED: // needs to be considered occupied not to leak a machine
                LOGGER.log(Level.WARNING, "Machine state not recognized by openstack4j, report this as a bug: " + server);
                return true;
            default:
                return true;
        }
    }

    private boolean isOurs(@Nonnull Server server) {
        return instanceFingerprint().equals(server.getMetadata().get(FINGERPRINT_KEY));
    }

    /**
     * Identification for instances launched by this instance via this plugin.
     *
     * @return Identifier to filter instances we control.
     */
    private @Nonnull String instanceFingerprint() {
        return Jenkins.getActiveInstance().getRootUrl();
    }

    public @Nonnull Server getServerById(@Nonnull String id) throws NoSuchElementException {
        Server server = clientProvider.get().compute().servers().get(id);
        if (server == null) throw new NoSuchElementException("No such server running: " + id);
        return server;
    }

    public @Nonnull List<Server> getServersByName(@Nonnull String name) {
        List<Server> ret = new ArrayList<>();
        for (Server server : clientProvider.get().compute().servers().list(Collections.singletonMap("name", name))) {
            if (isOurs(server)) {
                ret.add(server);
            }
        }
        return ret;
    }

    /**
     * Provision machine and wait until ready.
     *
     * @throws ActionFailed Openstack failed to provision the slave or it was in erroneous state (server will be deleted in such case).
     */
    public @Nonnull Server bootAndWaitActive(@Nonnull ServerCreateBuilder request, @Nonnegative int timeout) throws ActionFailed {
        debug("Booting machine");
        try {
            Server server = _bootAndWaitActive(request, timeout);
            if (server == null) {
                // Server failed to become ACTIVE in time. Find in what state it is, then.
                String name = request.build().getName();
                List<? extends Server> servers = getServersByName(name);

                String msg = "Failed to provision the server in time (" + timeout + "ms): " + servers.toString();

                ActionFailed err = new ActionFailed(msg);
                try {
                    // We do not have the id so can not be sure which one is our
                    int size = servers.size();
                    if (size == 1) {
                        // TODO async disposer
                        destroyServer(servers.get(0));
                    } else if (size > 1) {
                        LOGGER.warning("Unable to destroy server " + name + " as there is " + size + " of them");
                    }
                } catch (Throwable ex) {
                    err.addSuppressed(ex);
                }
                throw err;
            }
            debug("Machine started: " + server.getName());
            throwIfFailed(server);
            return server;
        } catch (ResponseException ex) {
            throw new ActionFailed(ex.getMessage(), ex);
        }
    }

    @Restricted(NoExternalUse.class) // Test hook
    public Server _bootAndWaitActive(@Nonnull ServerCreateBuilder request, @Nonnegative int timeout) {
        request.addMetadataItem(FINGERPRINT_KEY, instanceFingerprint());
        return clientProvider.get().compute().servers().bootAndWaitActive(request.build(), timeout);
    }

    /**
     * Fetch updated info about the server.
     */
    public @Nonnull Server updateInfo(@Nonnull Server server) {
        return getServerById(server.getId());
    }

    /**
     * Delete server eagerly.
     *
     * The deletion tends to fail a couple of time before it succeeds. This method throws on any such failure. Use
     * {@link DestroyMachine} to destroy the server reliably.
     */
    public void destroyServer(@Nonnull Server server) throws ActionFailed {
        String nodeId = server.getId();

        ComputeFloatingIPService fipsService = getComputeFloatingIPService();
        if (fipsService != null) {
            for (FloatingIP ip : fipsService.list()) {
                if (nodeId.equals(ip.getInstanceId())) {
                    ActionResponse res = fipsService.deallocateIP(ip.getId());
                    if (res.isSuccess()) {
                        debug("Deallocated Floating IP " + ip.getFloatingIpAddress());
                    } else {
                        throw new ActionFailed(
                                "Floating IP deallocation failed for " + ip.getFloatingIpAddress() + ": " + res.getFault() + "(" + res.getCode()  + ")"
                        );
                    }
                }
            }
        }

        ServerService servers = clientProvider.get().compute().servers();
        server = servers.get(nodeId);
        if (server == null || server.getStatus() == Server.Status.DELETED) {
            debug("Machine destroyed: " + nodeId);
            return; // Deleted
        }

        ActionResponse res = servers.delete(nodeId);
        if (res.getCode() == 404) {
            debug("Machine destroyed: " + nodeId);
            return; // Deleted
        }

        throwIfFailed(res);
    }

    /**
     * Assign floating ip address to the server.
     *
     * Note that after the successful assignment, the Server instance becomes outdated as it does not contain the IP details.
     *
     * @param server Server to assign FIP
     * @param poolName Name of the FIP pool to use. If null, openstack default pool will be used.
     */
    public @Nonnull FloatingIP assignFloatingIp(@Nonnull Server server, @CheckForNull String poolName) throws ActionFailed {
        debug("Allocating floating IP for " + server.getName());
        ComputeFloatingIPService fips = clientProvider.get().compute().floatingIps(); // This throws when user is not authorized to manipulate FIPs
        FloatingIP ip;
        try {
            ip = fips.allocateIP(poolName);
        } catch (ResponseException ex) {
            // TODO Grab some still IPs from JCloudsCleanupThread
            throw new ActionFailed(ex.getMessage() + " Allocating for " + server.getName(), ex);
        }
        debug("Floating IP allocated " + ip.getFloatingIpAddress());
        try {
            debug("Assigning floating IP to " + server.getName());
            ActionResponse res = fips.addFloatingIP(server, ip.getFloatingIpAddress());
            throwIfFailed(res);
            debug("Floating IP assigned");
        } catch (Throwable _ex) {
            ActionFailed ex = _ex instanceof ActionFailed
                    ? (ActionFailed) _ex
                    : new ActionFailed("Unable to assign floating IP for " + server.getName(), _ex)
            ;

            ActionResponse res = fips.deallocateIP(ip.getId());
            logIfFailed(res);
            throw ex;
        }

        return ip;
    }

    public void destroyFip(String fip) {
        ActionResponse delete = clientProvider.get().networking().floatingip().delete(fip);

        // Deleted by some other action. Being idempotent here and reporting success.
        if (delete.getCode() == 404) return;

        throwIfFailed(delete);
    }

    /**
     * Extract public address from server info.
     *
     * @return Floating IP, if there is none Fixed IP, null if there is none either.
     */
    public static @CheckForNull Address getPublicAddressObject(@Nonnull Server server) {
    	Address fixed = null;
        for (List<? extends Address> addresses: server.getAddresses().getAddresses().values()) {
            for (Address addr: addresses) {
                if ("floating".equals(addr.getType())) {
                    return addr;
                }

                fixed = addr;
            }
        }

        // No floating IP found - use fixed
        return fixed;
    }
    
    /**
     * Extract public address from server info.
     *
     * @return Floating IP, if there is none Fixed IP, null if there is none either.
     */
    public static @CheckForNull String getPublicAddress(@Nonnull Server server) {
        String fixed = null;
        for (List<? extends Address> addresses: server.getAddresses().getAddresses().values()) {
            for (Address addr: addresses) {
                if ("floating".equals(addr.getType())) {
                    return addr.getAddr();
                }

                fixed = addr.getAddr();
            }
        }

        // No floating IP found - use fixed
        return fixed;
    }

    /**
     * Extract public address from server info.
     *
     * @return Floating IP, if there is none Fixed IP, null if there is none either.
     */
    public static @CheckForNull String getPublicAddressIpv4(@Nonnull Server server) {
        String fixed = null;
        for (List<? extends Address> addresses: server.getAddresses().getAddresses().values()) {
            for (Address addr: addresses) {
                if ("floating".equals(addr.getType())) {
                    return addr.getAddr();
                } else if (addr.getVersion()==4) {
                	fixed = addr.getAddr();
                }
            }
        }

        // No floating IP found - use fixed
        return fixed;
    }

    /**
     * @return true if succeeded.
     */
    private static boolean logIfFailed(@Nonnull ActionResponse res) {
        if (res.isSuccess()) return true;
        LOGGER.log(Level.INFO, res.toString());
        return false;
    }

    private static void throwIfFailed(@Nonnull ActionResponse res) {
        if (res.isSuccess()) return;
        throw new ActionFailed(res.toString());
    }

    private void throwIfFailed(@Nonnull Server server) {
        Server.Status status = server.getStatus();
        if (status == Server.Status.ACTIVE) return; // Success

        StringBuilder sb = new StringBuilder();
        sb.append("Failed to boot server ").append(server.getName());
        if (status == Server.Status.BUILD) {
            sb.append(" in time:");
        } else {
            sb.append(":");
        }

        sb.append(" status=").append(status);
        sb.append(" vmState=").append(server.getVmState());
        Fault fault = server.getFault();
        String msg = fault == null
            ? "none"
            : String.format("%d: %s (%s)", fault.getCode(), fault.getMessage(), fault.getDetails())
        ;
        sb.append(" fault=").append(msg);

        // Destroy the server
        ActionFailed ex = new ActionFailed(sb.toString());
        try {
            // TODO async disposer
            destroyServer(server);
        } catch (ActionFailed suppressed) {
            ex.addSuppressed(suppressed);
        }
        LOGGER.log(Level.WARNING, "Machine provisioning failed: " + server, ex);
        throw ex;
    }

    /**
     * Perform some tests before calling the connection successfully established.
     */
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    public @CheckForNull Throwable sanityCheck() {
        // Try to talk to all endpoints the plugin rely on so we know they exist, are enabled, user have permission to
        // access them and JVM trusts their SSL cert.
        try {
            OSClient client = clientProvider.get();
            client.networking().network().get("");
            client.images().listMembers("");
            client.compute().listExtensions().size();
        } catch (Throwable ex) {
            return ex;
        }
        return null;
    }

    public static final class ActionFailed extends RuntimeException {
        public ActionFailed(String msg) {
            super(msg);
        }

        public ActionFailed(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private static void debug(@Nonnull String msg, @Nonnull String... args) {
        LOGGER.log(Level.FINE, msg, args);
    }

    @Restricted(NoExternalUse.class) // Extension point just for testing
    public static abstract class FactoryEP implements ExtensionPoint {
        private final transient @Nonnull Cache<String, Openstack> cache = CacheBuilder.newBuilder()
                // There is no clear reasoning behind particular expiration policy except that individual instances can
                // have different token expiration time, which is something guava does not support. This expiration needs
                // to be implemented separately.
                // According to OpenStack documentation, default token lifetime is one hour so let's use that as a baseline.
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build()
        ;

        public abstract @Nonnull Openstack getOpenstack(
                @Nonnull String endPointUrl, @Nonnull String identity, @Nonnull String credential, @CheckForNull String region
        ) throws FormValidation;

        /**
         * Instantiate Openstack client.
         */
        public static @Nonnull Openstack get(
                @Nonnull String endPointUrl, @Nonnull String identity, @Nonnull String credential, @CheckForNull String region
        ) throws FormValidation {
            String fingerprint = Util.getDigestOf(endPointUrl + '\n' + identity + '\n' + credential + '\n' + region);
            FactoryEP ep = ExtensionList.lookup(FactoryEP.class).get(0);

            Openstack cachedInstance = ep.cache.getIfPresent(fingerprint);
            if (cachedInstance == null || isExpired(cachedInstance)) {
                cachedInstance = ep.getOpenstack(endPointUrl, identity, credential, region);
                ep.cache.put(fingerprint, cachedInstance);
            }

            return cachedInstance;
        }

        public static @Nonnull FactoryEP replace(@Nonnull FactoryEP factory) {
            ExtensionList<Openstack.FactoryEP> lookup = ExtensionList.lookup(Openstack.FactoryEP.class);
            lookup.clear();
            lookup.add(factory);
            return factory;
        }
    }

    private static boolean isExpired(Openstack cachedInstance) {
        return cachedInstance.getExpires().compareTo(new Date()) < 1;
    }

    @Extension
    public static final class Factory extends FactoryEP {
        public @Nonnull Openstack getOpenstack(String endPointUrl, String identity, String credential, @CheckForNull String region) throws FormValidation {
            endPointUrl = Util.fixEmptyAndTrim(endPointUrl);
            identity = Util.fixEmptyAndTrim(identity);
            credential = Util.fixEmptyAndTrim(credential);
            region = Util.fixEmptyAndTrim(region);

            if (endPointUrl == null) throw FormValidation.error("No endPoint specified");
            if (identity == null) throw FormValidation.error("No identity specified");
            if (credential == null) throw FormValidation.error("No credential specified");

            return new Openstack(endPointUrl, identity, Secret.fromString(credential), region);
        }
    }

    /**
     * Abstract away the fact client can not be shared between threads and the implementation details for different
     * versions of keystone.
     */
    private static abstract class ClientProvider<T> {
        protected final T storage;

        private ClientProvider(T token) {
            storage = token;
        }

        /**
         * Reuse auth session between different threads creating separate client for every use.
         */
        public abstract @Nonnull OSClient get();

        protected abstract Date _getExpires();

        @Nonnull Date getExpires() {
            Date ex = _getExpires();
            if (ex == null) {
                throw new AssertionError("No expiration specified in " + storage);
            }
            return ex;
        }

        private static ClientProvider get(OSClient client) {
            if (client instanceof OSClient.OSClientV2) return new SessionClientV2Provider((OSClient.OSClientV2) client);
            if (client instanceof OSClient.OSClientV3) return new SessionClientV3Provider((OSClient.OSClientV3) client);

            throw new AssertionError(
                    "Unsupported openstack4j client " + client.getClass().getName()
            );
        }

        private static class SessionClientV2Provider extends ClientProvider<Access> {

            private SessionClientV2Provider(OSClient.OSClientV2 toStore) {
                super(toStore.getAccess());
            }

            public @Nonnull OSClient get() {
                return OSFactory.clientFromAccess(storage);
            }

            public Date _getExpires() {
                return storage.getToken().getExpires();
            }
        }

        private static class SessionClientV3Provider extends ClientProvider<Token> {

            private SessionClientV3Provider(OSClient.OSClientV3 toStore) {
                super(toStore.getToken());
            }

            public @Nonnull OSClient get() {
                return OSFactory.clientFromToken(storage);
            }

            public Date _getExpires() {
                return storage.getExpires();
            }
        }
    }

    static {
        // Log where guava is coming from. This can not be reliably tested as jenkins-test-harness, hpi:run and actual
        // jenkins deployed plugin have different classloader environments. Messing around with maven-hpi-plugin opts can
        // fix or break any of that and there is no regression test to catch that.
        try {
            File path = Which.jarFile(Objects.ToStringHelper.class);
            LOGGER.info("com.google.common.base.Objects loaded from " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to get source of com.google.common.base.Objects", e);
        }
    }
}
