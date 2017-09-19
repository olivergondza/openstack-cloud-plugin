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
package jenkins.plugins.openstack.compute;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.google.common.base.Joiner;
import com.trilead.ssh2.Connection;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.RelativePath;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.ItemGroup;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.ReflectionUtils;
import jenkins.model.Jenkins;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.openstack4j.api.exceptions.AuthenticationException;
import org.openstack4j.api.exceptions.ConnectionException;
import org.openstack4j.model.compute.Flavor;
import org.openstack4j.model.compute.ext.AvailabilityZone;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.storage.block.VolumeSnapshot;
import org.springframework.util.StringUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author ogondza.
 */
@Extension @Restricted(NoExternalUse.class)
public final class SlaveOptionsDescriptor extends hudson.model.Descriptor<SlaveOptions> {
    private static final Logger LOGGER = Logger.getLogger(SlaveOptionsDescriptor.class.getName());
    private static final FormValidation OK = FormValidation.ok();
    private static final FormValidation REQUIRED = FormValidation.error(hudson.util.Messages.FormValidation_ValidateRequired());

    public SlaveOptionsDescriptor() {
        super(SlaveOptions.class);
    }

    @Override
    public String getDisplayName() {
        return "Slave Options";
    }

    private SlaveOptions opts() {
        return ((JCloudsCloud.DescriptorImpl) Jenkins.getActiveInstance().getDescriptorOrDie(JCloudsCloud.class)).getDefaultOptions();
    }

    private String getDefault(String d1, Object d2) {
        d1 = Util.fixEmpty(d1);
        if (d1 != null) return d1;
        if (d2 != null) return Util.fixEmpty(String.valueOf(d2));
        return null;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckInstanceCap(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("instanceCap") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getInstanceCap());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckStartTimeout(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("startTimeout") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getStartTimeout());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckNumExecutors(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("numExecutors") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getNumExecutors());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return FormValidation.validatePositiveInteger(value);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckRetentionTime(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("retentionTime") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getRetentionTime());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        try {
            if (Integer.parseInt(value) == -1)
                return FormValidation.ok("Keep forever");
        } catch (NumberFormatException e) {
        }
        return FormValidation.validateNonNegativeInteger(value);
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ListBoxModel doFillFloatingIpPoolItems(
            @QueryParameter String floatingIpPool,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(floatingIpPool);
        try {
            if (haveAuthDetails(endPointUrl, identity, credential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                for (String p : openstack.getSortedIpPools()) {
                    m.add(p);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(valueOrEmpty);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckFloatingIpPool(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("floatingIpPool") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getFloatingIpPool());
            if (d != null) return FormValidation.ok(def(d));
            // Not required
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ListBoxModel doFillHardwareIdItems(
            @QueryParameter String hardwareId,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(hardwareId);
        try {
            if (haveAuthDetails(endPointUrl, identity, credential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                for (Flavor flavor : openstack.getSortedFlavors()) {
                    final String value = flavor.getId();
                    final String displayText = String.format("%s (%s)", flavor.getName(), value);
                    m.add(displayText, value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(hardwareId);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckHardwareId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("hardwareId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getHardwareId());
            if (d != null) return FormValidation.ok(def(d));
            return REQUIRED;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillBootSourceItems(
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", null);
        for (JCloudsCloud.BootSource option : JCloudsCloud.BootSource.values()) {
            m.add(option.toDisplayName(), option.name());
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckBootSource(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("bootSource") String def
    ) {
        final JCloudsCloud.BootSource valueOrNull = JCloudsCloud.BootSource.fromString(value);
        if (valueOrNull != null) return OK;
        final JCloudsCloud.BootSource effective = calcBootSource(value, def);
        if (effective != null) return FormValidation.ok(def(effective.toDisplayName()));
        return REQUIRED;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    /*
     * Maintenance note:
     * The parameters for this method are messy for a reason.
     * A slave template depends on the template's own "bootSource", the cloud-default "bootSource",
     * and the template's own "imageId".
     * The cloud-default depends on its own "bootSource" and its own "imageId".
     * In the case of the slave template, Jenkins can't pass us the values for different fields (one from
     * the cloud default, one from the slave template) with the same name.
     * If you try, it passes the value from one of those same-named fields field to all parameters that
     * requested a field with that name, ignoring the @RelativePath.
     * i.e. doFillXXXItems(@QueryParameter String foo, @RelativePath("../../slaveOptions") @QueryParameter("foo") bar)
     * does not work as expected - both parameters get passed the same value.
     * To workaround this, we have a hidden (non-persisted) second field called "copyOfBootSource" which (always) contains
     * the same value as "bootSource" so we can read that (as it's got a different name) from the cloud-default SlaveOptions.
     */
    public ListBoxModel doFillImageIdItems(
            @QueryParameter String bootSource,
            @RelativePath("../../slaveOptions") @QueryParameter("copyOfBootSource") String defBootSource,
            @QueryParameter String imageId,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        final String valueOrEmpty = Util.fixNull(imageId);
        m.add(new ListBoxModel.Option("None specified", "", valueOrEmpty.isEmpty()));
        try {
            final JCloudsCloud.BootSource effectiveBS = calcBootSource(bootSource, defBootSource);
            if (effectiveBS!=null && haveAuthDetails(endPointUrl, identity, credential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                final List<String> values = effectiveBS.listAllNames(openstack);
                for (String value : values) {
                    final String displayText = effectiveBS.toDisplayName() + " " + value;
                    m.add(displayText, value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(imageId);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckImageId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("imageId") String def,
            @QueryParameter String bootSource,
            @RelativePath("../../slaveOptions") @QueryParameter("copyOfBootSource") String defBootSource,
            // authentication fields can be in two places relative to us.
            @RelativePath("..") @QueryParameter("endPointUrl") String endPointUrlCloud,
            @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
            @RelativePath("..") @QueryParameter("identity") String identityCloud,
            @RelativePath("../..") @QueryParameter("identity") String identityTemplate,
            @RelativePath("..") @QueryParameter("credential") String credentialCloud,
            @RelativePath("../..") @QueryParameter("credential") String credentialTemplate,
            @RelativePath("..") @QueryParameter("zone") String zoneCloud,
            @RelativePath("../..") @QueryParameter("zone") String zoneTemplate
    ) {
        final JCloudsCloud.BootSource effectiveBootSource = calcBootSource(bootSource, defBootSource);
        if (Util.fixEmpty(value) != null) {
            List<String> matches = null;
            try {
                final String endPointUrl = getDefault(endPointUrlCloud, endPointUrlTemplate);
                final String identity = getDefault(identityCloud, identityTemplate);
                final String credential = getDefault(credentialCloud, credentialTemplate);
                final String zone = getDefault(zoneCloud, zoneTemplate);
                if (effectiveBootSource!=null && haveAuthDetails(endPointUrl, identity, credential, zone)) {
                    final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                    matches = effectiveBootSource.findMatchingIds(openstack, value);
                }
            } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                return FormValidation.warning(ex, "Unable to validate.");
            } catch (Exception ex) {
                LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                return FormValidation.warning(ex, "Unable to validate.");
            }
            if( matches!=null) {
                final int numberOfMatches = matches.size();
                if( numberOfMatches < 1 ) return FormValidation.error(effectiveBootSource.toDisplayName()+" \""+value+"\" not found.");
                if( numberOfMatches > 1 ) return FormValidation.warning(effectiveBootSource.toDisplayName()+" \""+value+"\" is ambiguous.");
                return OK;
            }
            return FormValidation.warning("Unable to validate.");
        }
        final JCloudsCloud.BootSource parentBootSource = calcBootSource(defBootSource, defBootSource);
        final String effectiveValueOrNull;
        if ( effectiveBootSource!=null && parentBootSource!=null && effectiveBootSource.equals(parentBootSource)) {
            // our default image is only valid if it's the same bootSource
            effectiveValueOrNull = getDefault(def, opts().getImageId());
        } else {
            effectiveValueOrNull = null;
        }
        if( effectiveValueOrNull==null ) return REQUIRED;
        return FormValidation.ok(def(effectiveValueOrNull));
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ListBoxModel doFillNetworkIdItems(
            @QueryParameter String networkId,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(networkId);
        try {
            if (haveAuthDetails(endPointUrl, identity, credential, zone)) {
                Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                for (org.openstack4j.model.network.Network network : openstack.getSortedNetworks()) {
                    final String value = network.getId();
                    final String displayText = String.format("%s (%s)", network.getName(), value);
                    m.add(displayText, value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(networkId);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckNetworkId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("networkId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getNetworkId());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillSlaveTypeItems() {
        ListBoxModel items = new ListBoxModel();
        items.add("None specified", null);
        items.add("SSH", "SSH");
        items.add("JNLP", "JNLP");
        return items;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckSlaveType(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("slaveType") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getSlaveType());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context) {
        if (!(context instanceof AccessControlled ? (AccessControlled) context : Jenkins.getActiveInstance()).hasPermission(Computer.CONFIGURE)) {
            return new ListBoxModel();
        }
        List<StandardUsernameCredentials> credentials = CredentialsProvider.lookupCredentials(
                StandardUsernameCredentials.class, context, ACL.SYSTEM, SSHLauncher.SSH_SCHEME
        );
        return new StandardUsernameListBoxModel()
                .withMatching(SSHAuthenticator.matcher(Connection.class), credentials)
                .withEmptySelection()
        ;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckCredentialsId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("credentialsId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getCredentialsId());
            if (d != null) {
                d = CredentialsNameProvider.name(SSHLauncher.lookupSystemCredentials(d)); // ID to name
                return FormValidation.ok(def(d));
            }
            return REQUIRED;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public ListBoxModel doFillUserDataIdItems() {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        ConfigProvider provider = getConfigProvider();
        for (Config config : provider.getAllConfigs()) {
            m.add(config.name, config.id);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckUserDataId(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("userDataId") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getUserDataId());
            if (d != null) {
                String name = getConfigProvider().getConfigById(d).name;
                return getUserDataLink(d, def(name));
            }
            return OK;
        }
        return getUserDataLink(value, "View file");
    }

    private FormValidation getUserDataLink(String id, String name) {
        return FormValidation.okWithMarkup(
                "<a target='_blank' href='" + Jenkins.getActiveInstance().getRootUrl() + "configfiles/editConfig?id=" + Util.escape(id) + "'>" + Util.escape(name) + "</a>"
        );
    }

    private ConfigProvider getConfigProvider() {
        ExtensionList<ConfigProvider> providers = ConfigProvider.all();
        return providers.get(UserDataConfig.UserDataConfigProvider.class);
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckSecurityGroups(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("securityGroups") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getSecurityGroups());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ComboBoxModel doFillAvailabilityZoneItems(
            @QueryParameter String availabilityZone,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {
        // Support for availabilityZones is optional in OpenStack, so this is a f:combobox not f:select field.
        // Therefore we suggest some options if we can, but if we can't then we assume it's because they're not needed.
        final ComboBoxModel m = new ComboBoxModel();
        try {
            if (haveAuthDetails(endPointUrl, identity, credential, zone)) {
                final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                for (final AvailabilityZone az : openstack.getAvailabilityZones()) {
                    final String value = az.getZoneName();
                    m.add(value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckAvailabilityZone(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("availabilityZone") String def,
            // authentication fields can be in two places relative to us.
            @RelativePath("..") @QueryParameter("endPointUrl") String endPointUrlCloud,
            @RelativePath("../..") @QueryParameter("endPointUrl") String endPointUrlTemplate,
            @RelativePath("..") @QueryParameter("identity") String identityCloud,
            @RelativePath("../..") @QueryParameter("identity") String identityTemplate,
            @RelativePath("..") @QueryParameter("credential") String credentialCloud,
            @RelativePath("../..") @QueryParameter("credential") String credentialTemplate,
            @RelativePath("..") @QueryParameter("zone") String zoneCloud,
            @RelativePath("../..") @QueryParameter("zone") String zoneTemplate
    ) throws FormValidation {
        // Warn user if they've not selected anything AND there's multiple availability zones
        // as this can lead to non-deterministic behavior.
        // But if we can't find any availability zones then we assume that all is OK
        // because not all OpenStack deployments support them.
        if (Util.fixEmpty(value) == null) {
            final String d = getDefault(def, opts().getAvailabilityZone());
            if (d != null) return FormValidation.ok(def(d));
            final String endPointUrl = getDefault(endPointUrlCloud, endPointUrlTemplate);
            final String identity = getDefault(identityCloud, identityTemplate);
            final String credential = getDefault(credentialCloud, credentialTemplate);
            final String zone = getDefault(zoneCloud, zoneTemplate);
            if (haveAuthDetails(endPointUrl, identity, credential, zone)) {
                try {
                    final Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                    final int numberOfAZs = openstack.getAvailabilityZones().size();
                    if (numberOfAZs > 1) {
                        return FormValidation.warning("Ambiguity warning: Multiple zones found.");
                    }
                } catch (AuthenticationException | FormValidation | ConnectionException ex) {
                    LOGGER.log(Level.FINEST, "Openstack call failed", ex);
                } catch (Exception ex) {
                    LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
                }
            }
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    @InjectOsAuth
    public ListBoxModel doFillKeyPairNameItems(
            @QueryParameter String keyPairName,
            @QueryParameter String endPointUrl, @QueryParameter String identity, @QueryParameter String credential, @QueryParameter String zone
    ) {
        ListBoxModel m = new ListBoxModel();
        m.add("None specified", "");
        final String valueOrEmpty = Util.fixNull(keyPairName);
        try {
            if (haveAuthDetails(endPointUrl, identity, credential, zone)) {
                Openstack openstack = Openstack.Factory.get(endPointUrl, identity, credential, zone);
                for (String value : openstack.getSortedKeyPairNames()) {
                    m.add(value);
                }
            }
        } catch (AuthenticationException | FormValidation | ConnectionException ex) {
            LOGGER.log(Level.FINEST, "Openstack call failed", ex);
        } catch (Exception ex) {
            LOGGER.log(Level.SEVERE, ex.getMessage(), ex);
        }
        if (!hasValue(m, valueOrEmpty)) {
            m.add(keyPairName);
        }
        return m;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckKeyPairName(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("keyPairName") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getKeyPairName());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckJvmOptions(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("jvmOptions") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getJvmOptions());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    @Restricted(DoNotUse.class)
    public FormValidation doCheckFsRoot(
            @QueryParameter String value,
            @RelativePath("../../slaveOptions") @QueryParameter("fsRoot") String def
    ) {
        if (Util.fixEmpty(value) == null) {
            String d = getDefault(def, opts().getFsRoot());
            if (d != null) return FormValidation.ok(def(d));
            return OK;
        }
        return OK;
    }

    /**
     * Get Default value label.
     */
    @Restricted(DoNotUse.class) // For view
    public @Nonnull String def(@CheckForNull Object val) {
        return val == null ? "" : ("Inherited value: " + val);
    }

    /**
     * Add dependencies on credentials
     */
    @Override
    public void calcFillSettings(String field, Map<String, Object> attributes) {
        super.calcFillSettings(field, attributes);

        List<String> deps = new ArrayList<>();
        String fillDependsOn = (String) attributes.get("fillDependsOn");
        if (fillDependsOn != null) {
            deps.addAll(Arrays.asList(fillDependsOn.split(" ")));
        }

        String capitalizedFieldName = StringUtils.capitalize(field);
        String methodName = "doFill" + capitalizedFieldName + "Items";
        Method method = ReflectionUtils.getPublicMethodNamed(getClass(), methodName);

        // Replace direct reference to references to possible relative paths
        if (method.getAnnotation(InjectOsAuth.class) != null) {
            for (String attr: Arrays.asList("endPointUrl", "identity", "credential", "zone")) {
                deps.remove(attr);
                deps.add("../" + attr);
                deps.add("../../" + attr);
            }
        }

        if (!deps.isEmpty()) {
            attributes.put("fillDependsOn", Joiner.on(' ').join(deps));
        }
    }

    private static boolean hasValue(ListBoxModel m, String value) {
        for( final ListBoxModel.Option o : m) {
            if ( Objects.equals(value, o.value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean haveAuthDetails(String endPointUrl, String identity, String credential, String zone) {
        return Util.fixEmpty(endPointUrl)!=null && Util.fixEmpty(identity)!=null && Util.fixEmpty(credential)!=null;
    }

    private JCloudsCloud.BootSource calcBootSource(String bootSource, String defaultBootSource) {
        final JCloudsCloud.BootSource bsEnum = JCloudsCloud.BootSource.fromString(bootSource);
        if( bsEnum!=null) return bsEnum;
        final JCloudsCloud.BootSource defBSEnum = JCloudsCloud.BootSource.fromString(defaultBootSource);
        if( defBSEnum!=null) return defBSEnum;
        final JCloudsCloud.BootSource globalDefault = opts().getBootSource();
        return globalDefault;
    }

    @Retention(RUNTIME)
    @Target({METHOD})
    private @interface InjectOsAuth {}
}
