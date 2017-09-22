/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
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
package jenkins.plugins.openstack.compute.slaveopts;

import hudson.Extension;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.plugins.openstack.compute.JCloudsCloud;
import jenkins.plugins.openstack.compute.OsAuthDescriptor;
import jenkins.plugins.openstack.compute.internal.Openstack;
import net.sf.json.JSONObject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.openstack4j.api.Builders;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.exceptions.ConnectionException;
import org.openstack4j.model.compute.BDMDestType;
import org.openstack4j.model.compute.BDMSourceType;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.BlockDeviceMappingBuilder;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static jenkins.plugins.openstack.compute.SlaveOptionsDescriptor.REQUIRED;
import static jenkins.plugins.openstack.compute.SlaveOptionsDescriptor.getDefault;

/**
 * The source machine is booted from.
 */
@Restricted(NoExternalUse.class)
public abstract class BootSource extends AbstractDescribableImpl<BootSource> implements Serializable {
    private static final long serialVersionUID = -838838433829383008L;
    private static final Logger LOGGER = Logger.getLogger(BootSource.class.getName());

    /**
     * Configures the given {@link ServerCreateBuilder} to specify that the
     * newly provisioned server should boot from the specified ID.
     *
     * @param builder The server specification that is under construction. This
     *                will be amended.
     * @param os Openstack.
     * @throws JCloudsCloud.ProvisioningFailedException Unable to configure the request. Do not provision.
     */
    public void setServerBootSource(@Nonnull ServerCreateBuilder builder, @Nonnull Openstack os) throws JCloudsCloud.ProvisioningFailedException {}

    /**
     * Called after a server has been provisioned.
     *
     * @param server    The newly-provisioned server.
     * @param openstack Means of communicating with the OpenStack service.
     * @throws JCloudsCloud.ProvisioningFailedException Unable to amend the server so it has to be rolled-back.
     */
    public void afterProvisioning(@Nonnull Server server, @Nonnull Openstack openstack) throws JCloudsCloud.ProvisioningFailedException {}

    @Override public BootSourceDescriptor getDescriptor() {
        return (BootSourceDescriptor) super.getDescriptor();
    }

    public abstract static class BootSourceDescriptor extends OsAuthDescriptor<BootSource> {
        @Override
        public List<String> getAuthFieldsOffsets() {
            return Arrays.asList("../..", "../../..");
        }

        /**
         * Lists all the IDs (of this kind of {@link BootSource}) matching the
         * given nameOrId.
         *
         * @param openstack Means of communicating with the OpenStack service.
         * @param nameOrId  The user's selected name (or ID).
         */
        public abstract @Nonnull List<String> findMatchingIds(Openstack openstack, String nameOrId);
    }

    public static final class Image extends BootSource {
        private static final long serialVersionUID = -8309975034351235331L;

        private final @Nonnull String name;

        @DataBoundConstructor
        public Image(@Nonnull String name) {
            this.name = name;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        @Override
        public void setServerBootSource(@Nonnull ServerCreateBuilder builder, @Nonnull Openstack os) throws JCloudsCloud.ProvisioningFailedException {
            List<String> matchingIds = getDescriptor().findMatchingIds(os, name);
            int size = matchingIds.size();
            // TODO
            if (size == 0) return; //throw new JCloudsCloud.ProvisioningFailedException("No image matching " + name + " found");

            final String id;
            if (size == 1) {
                id = matchingIds.get(0);
            } else {
                id = matchingIds.get(size - 1);
                LOGGER.warning(id + " images matches " + name + ". Using the most recent one: " + id);
            }
            builder.image(id);
        }

        @Override
        public String toString() {
            return "Image " + name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Image image = (Image) o;

            return this.name.equals(image.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Extension
        public static final class Desc extends BootSourceDescriptor {
            @Override
            public @Nonnull String getDisplayName() {
                return "Image";
            }

            @Nonnull @Override
            public List<String> findMatchingIds(Openstack openstack, String nameOrId) {
                return openstack.getImageIdsFor(nameOrId);
            }

            @Restricted(DoNotUse.class)
            @InjectOsAuth
            public ListBoxModel doFillNameItems(
                    @QueryParameter String name,
                    @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
            ) {

                ListBoxModel m = new ListBoxModel();
                m.add("None specified", "");

                try {
                    final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                    for (String candidateName: openstack.getImages().keySet()) {
                        m.add(candidateName);
                    }
                    return m;
                } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                    LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                }

                if (Util.fixEmpty(name) != null) {
                    m.add(name);
                }

                return m;
            }

            @Restricted(DoNotUse.class)
            public FormValidation doCheckName(
                    @QueryParameter String value,
                    // authentication fields can be in two places relative to us.
                    @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlCloud,
                    @RelativePath("../../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
                    @RelativePath("../..") @QueryParameter("identity") String identityCloud,
                    @RelativePath("../../..") @QueryParameter("identity") String identityTemplate,
                    @RelativePath("../..") @QueryParameter("credential") String credentialCloud,
                    @RelativePath("../../..") @QueryParameter("credential") String credentialTemplate,
                    @RelativePath("../..") @QueryParameter("zone") String zoneCloud,
                    @RelativePath("../../..") @QueryParameter("zone") String zoneTemplate
            ) {
                if (Util.fixEmpty(value) == null) return REQUIRED;

                final String endPointUrl = getDefault(endPointUrlCloud, endPointUrlTemplate);
                final String identity = getDefault(identityCloud, identityTemplate);
                final String credential = getDefault(credentialCloud, credentialTemplate);
                final String zone = getDefault(zoneCloud, zoneTemplate);
                if (!haveAuthDetails(endPointUrl, identity, credential, zone)) return FormValidation.ok();

                List<String> matches;
                try {
                    final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                    matches = findMatchingIds(openstack, value);
                } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                    LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                    return FormValidation.warning(ex, "Unable to validate");
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                    return FormValidation.warning(ex, "Unable to validate");
                }

                final int numberOfMatches = matches.size();
                if (numberOfMatches < 1 ) return FormValidation.error("Not found");
                if (numberOfMatches > 1 ) return FormValidation.warning("Multiple matching results");
                return FormValidation.ok();
            }
        }
    }

    public static final class VolumeSnapshot extends BootSource {
        private static final long serialVersionUID = 1629434277902240395L;
        private final @Nonnull String name;

        @DataBoundConstructor
        public VolumeSnapshot(@Nonnull String name) {
            this.name = name;
        }

        @Nonnull
        public String getName() {
            return name;
        }

        @Override
        public void setServerBootSource(@Nonnull ServerCreateBuilder builder, @Nonnull Openstack os) {
            List<String> matchingIds = getDescriptor().findMatchingIds(os, name);
            int size = matchingIds.size();
            // TODO
            if (size == 0) return; // throw new JCloudsCloud.ProvisioningFailedException("No volume snapshot matching " + name + " found");

            final String id;
            if (size == 1) {
                id = matchingIds.get(0);
            } else {
                id = matchingIds.get(size - 1);
                LOGGER.warning(id + " volume snapshots matches " + name + ". Using the most recent one: " + id);
            }
            builder.image(id);

            BlockDeviceMappingBuilder volumeBuilder = Builders.blockDeviceMapping()
                    .sourceType(BDMSourceType.SNAPSHOT)
                    .destinationType(BDMDestType.VOLUME)
                    .uuid(id)
                    .deleteOnTermination(true)
                    .bootIndex(0);
            builder.blockDevice(volumeBuilder.build());
        }

        @Override
        public void afterProvisioning(@Nonnull Server server, @Nonnull Openstack openstack) {
            /*
             * OpenStack creates a Volume for the Instance to boot from but
             * it does not give that Volume a name or description. We do
             * this so that humans can recognize those Volumes.
             */
            final List<String> volumeIds = server.getOsExtendedVolumesAttached();
            final String instanceId = server.getId();
            final String instanceName = server.getName();
            int i = 0;
            final String newVolumeDescription = "For " + instanceName + " (" + instanceId
                    + "), from VolumeSnapshot " + name + ".";
            for (final String volumeId : volumeIds) {
                final String newVolumeName = instanceName + '[' + (i++) + ']';
                openstack.setVolumeNameAndDescription(volumeId, newVolumeName, newVolumeDescription);
            }
        }

        @Override
        public String toString() {
            return "VolumeSnapshot " + name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            VolumeSnapshot that = (VolumeSnapshot) o;

            return name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Extension
        public static final class Desc extends BootSourceDescriptor {
            @Override
            public @Nonnull String getDisplayName() {
                return "Volume Snapshot";
            }

            @Nonnull @Override
            public List<String> findMatchingIds(Openstack openstack, String nameOrId) {
                return openstack.getVolumeSnapshotIdsFor(nameOrId);
            }

            @Restricted(DoNotUse.class)
            @OsAuthDescriptor.InjectOsAuth
            public ListBoxModel doFillNameItems(
                    @QueryParameter String name,
                    @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
            ) {

                ListBoxModel m = new ListBoxModel();
                m.add("None specified", "");

                try {
                    final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                    for (String candidateName: openstack.getVolumeSnapshots().keySet()) {
                        m.add(candidateName);
                    }
                    return m;
                } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                    LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                }

                if (Util.fixEmpty(name) != null) {
                    m.add(name);
                }

                return m;
            }

            @Restricted(DoNotUse.class)
            public FormValidation doCheckName(
                    @QueryParameter String value,
                    // authentication fields can be in two places relative to us.
                    @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlCloud,
                    @RelativePath("../../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
                    @RelativePath("../..") @QueryParameter("identity") String identityCloud,
                    @RelativePath("../../..") @QueryParameter("identity") String identityTemplate,
                    @RelativePath("../..") @QueryParameter("credential") String credentialCloud,
                    @RelativePath("../../..") @QueryParameter("credential") String credentialTemplate,
                    @RelativePath("../..") @QueryParameter("zone") String zoneCloud,
                    @RelativePath("../../..") @QueryParameter("zone") String zoneTemplate
            ) {
                if (Util.fixEmpty(value) == null) return REQUIRED;

                final String endPointUrl = getDefault(endPointUrlCloud, endPointUrlTemplate);
                final String identity = getDefault(identityCloud, identityTemplate);
                final String credential = getDefault(credentialCloud, credentialTemplate);
                final String zone = getDefault(zoneCloud, zoneTemplate);
                if (!haveAuthDetails(endPointUrl, identity, credential, zone)) return FormValidation.ok();

                List<String> matches;
                try {
                    final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                    matches = findMatchingIds(openstack, value);
                } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                    LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                    return FormValidation.warning(ex, "Unable to validate");
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                    return FormValidation.warning(ex, "Unable to validate");
                }

                final int numberOfMatches = matches.size();
                if (numberOfMatches < 1 ) return FormValidation.error("Not found");
                if (numberOfMatches > 1 ) return FormValidation.warning("Multiple matching results");
                return FormValidation.ok();
            }
        }
    }

    /**
     * No boot source specified. This exists only as a field in UI dropdown to be read by stapler and converted to plain old null.
     */
    // Therefore, noone refers to this as a symbol or tries to serialize it, ever.
    @SuppressWarnings({"unused", "serial"})
    public static final class Unspecified extends BootSource {
        private Unspecified() {} // Never instantiate


        @Extension(ordinal = Double.MAX_VALUE) // Make it first and therefore default
        public static final class Desc extends Descriptor<BootSource> {
            @Override public @Nonnull String getDisplayName() {
                return "Inherit / Override later";
            }

            @Override public BootSource newInstance(StaplerRequest req, @Nonnull JSONObject formData) throws FormException {
                return null; // Make sure this is never instantiated and hence will be treated as absent
            }
        }
    }
}
