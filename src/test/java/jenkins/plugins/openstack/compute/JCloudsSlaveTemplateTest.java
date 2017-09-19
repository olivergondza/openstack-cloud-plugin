package jenkins.plugins.openstack.compute;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import com.gargoylesoftware.htmlunit.html.HtmlForm;
import hudson.remoting.Base64;
import jenkins.plugins.openstack.PluginTestRule;

import jenkins.plugins.openstack.compute.internal.Openstack;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.openstack4j.model.compute.BDMDestType;
import org.openstack4j.model.compute.BDMSourceType;
import org.openstack4j.model.compute.BlockDeviceMappingCreate;
import org.openstack4j.model.compute.Server;
import org.openstack4j.model.compute.builder.ServerCreateBuilder;
import org.openstack4j.openstack.compute.domain.NovaBlockDeviceMappingCreate;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class JCloudsSlaveTemplateTest {

    @Rule
    public PluginTestRule j = new PluginTestRule();

    final String TEMPLATE_PROPERTIES = "name,labelString";
    final String CLOUD_PROPERTIES = "name,identity,credential,endPointUrl,zone";

    @Test
    public void configRoundtrip() throws Exception {
        JCloudsSlaveTemplate originalTemplate = new JCloudsSlaveTemplate(
                "test-template", "openstack-slave-type1 openstack-type2", j.dummySlaveOptions()
        );

        JCloudsCloud originalCloud = new JCloudsCloud(
                "my-openstack", "identity", "credential", "endPointUrl", "zone",
                SlaveOptions.empty(),
                Collections.singletonList(originalTemplate)
        );

        j.jenkins.clouds.add(originalCloud);
        HtmlForm form = j.createWebClient().goTo("configure").getFormByName("config");

        j.submit(form);

        final JCloudsCloud actualCloud = JCloudsCloud.getByName("my-openstack");
        j.assertEqualBeans(originalCloud, actualCloud, CLOUD_PROPERTIES);
        assertThat(actualCloud.getEffectiveSlaveOptions(), equalTo(originalCloud.getEffectiveSlaveOptions()));
        assertThat(actualCloud.getRawSlaveOptions(), equalTo(originalCloud.getRawSlaveOptions()));

        JCloudsSlaveTemplate actualTemplate = actualCloud.getTemplate("test-template");
        j.assertEqualBeans(originalTemplate, actualTemplate, TEMPLATE_PROPERTIES);
        assertThat(actualTemplate.getEffectiveSlaveOptions(), equalTo(originalTemplate.getEffectiveSlaveOptions()));
        assertThat(actualTemplate.getRawSlaveOptions(), equalTo(originalTemplate.getRawSlaveOptions()));
    }

    @Test
    public void eraseDefaults() throws Exception {
        SlaveOptions cloudOpts = SlaveOptionsTest.CUSTOM; // Make sure nothing collides with defaults
        SlaveOptions templateOpts = cloudOpts.getBuilder().bootSource(JCloudsCloud.BootSource.IMAGE).imageId("42").availabilityZone("other").build();
        assertEquals(cloudOpts.getHardwareId(), templateOpts.getHardwareId());

        JCloudsSlaveTemplate template = new JCloudsSlaveTemplate(
                "test-templateOpts", "openstack-slave-type1 openstack-type2", templateOpts
        );

        JCloudsCloud cloud = new JCloudsCloud(
                "my-openstack", "identity", "credential", "endPointUrl", "zone",
                cloudOpts,
                Collections.singletonList(template)
        );

        assertEquals(cloudOpts, cloud.getRawSlaveOptions());
        assertEquals(SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.IMAGE).imageId("42").availabilityZone("other").build(), template.getRawSlaveOptions());
    }

    @Test
    public void replaceUserData() throws Exception {
        SlaveOptions opts = j.dummySlaveOptions();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts,"a");
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provision(cloud);

        ArgumentCaptor<ServerCreateBuilder> captor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        verify(os).bootAndWaitActive(captor.capture(), anyInt());

        Properties actual = new Properties();
        actual.load(new ByteArrayInputStream(Base64.decode(captor.getValue().build().getUserData())));
        assertEquals(opts.getFsRoot(), actual.getProperty("SLAVE_JENKINS_HOME"));
        assertEquals(opts.getJvmOptions(), actual.getProperty("SLAVE_JVM_OPTIONS"));
        assertEquals(j.getURL().toExternalForm(), actual.getProperty("JENKINS_URL"));
        assertEquals("a", actual.getProperty("SLAVE_LABELS"));
        assertEquals("${unknown} ${VARIABLE}", actual.getProperty("DO_NOT_REPLACE_THIS"));
    }

    @Test
    public void noFloatingPoolId() throws Exception {
        SlaveOptions opts = j.dummySlaveOptions().getBuilder().floatingIpPool(null).build();
        JCloudsSlaveTemplate template = j.dummySlaveTemplate(opts,"a");
        JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(template));
        Openstack os = cloud.getOpenstack();

        template.provision(cloud);

        verify(os).bootAndWaitActive(any(ServerCreateBuilder.class), anyInt());
        verify(os, never()).assignFloatingIp(any(Server.class), any(String.class));
    }

    @Test
    public void bootFromVolumeSnapshot() throws Exception {
        final String volumeSnapshotName = "MyVolumeSnapshot";
        final String volumeSnapshotId = "vs-123-id";
        final SlaveOptions opts = j.dummySlaveOptions().getBuilder().bootSource(JCloudsCloud.BootSource.VOLUMESNAPSHOT).imageId(volumeSnapshotName).build();
        final JCloudsSlaveTemplate instance = j.dummySlaveTemplate(opts, "a");
        final JCloudsCloud cloud = j.configureSlaveProvisioning(j.dummyCloud(instance));
        final Openstack mockOs = cloud.getOpenstack();
        when(mockOs.getVolumeSnapshotIdsFor(volumeSnapshotName)).thenReturn(Collections.singletonList(volumeSnapshotId));
        final ArgumentCaptor<ServerCreateBuilder> scbCaptor = ArgumentCaptor.forClass(ServerCreateBuilder.class);
        final ArgumentCaptor<String> vnCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> vdCaptor = ArgumentCaptor.forClass(String.class);

        final Server actual = instance.provision(cloud);

        final String actualServerName = actual.getName();
        final String actualServerId = actual.getId();
        verify(mockOs, times(1)).bootAndWaitActive(scbCaptor.capture(), anyInt());
        verify(mockOs, times(1)).setVolumeNameAndDescription(anyString(), vnCaptor.capture(), vdCaptor.capture());
        final ServerCreateBuilder scbActual = scbCaptor.getValue();
        final List<BlockDeviceMappingCreate> blockDeviceMappingActual = (List<BlockDeviceMappingCreate>)readPrivateField(readPrivateField(scbActual, "m"), "blockDeviceMapping");
        assertThat(blockDeviceMappingActual, hasSize(1));
        final NovaBlockDeviceMappingCreate bdmcActual = (NovaBlockDeviceMappingCreate)blockDeviceMappingActual.get(0);
        assertThat(bdmcActual.boot_index, equalTo(0));
        assertThat(bdmcActual.delete_on_termination, equalTo(true));
        assertThat(bdmcActual.uuid, equalTo(volumeSnapshotId));
        assertThat(bdmcActual.source_type, equalTo(BDMSourceType.SNAPSHOT));
        assertThat(bdmcActual.destination_type, equalTo(BDMDestType.VOLUME));
        final String actualVolumeName = vnCaptor.getValue();
        assertThat(actualVolumeName, equalTo(actualServerName+"[0]"));
        final String actualVolumeDescription = vdCaptor.getValue();
        assertThat(actualVolumeDescription, containsString(actualServerName));
        assertThat(actualVolumeDescription, containsString(actualServerId));
        assertThat(actualVolumeDescription, containsString(volumeSnapshotId));
    }

    private static Object readPrivateField(Object object, String fieldName) {
        final StringBuilder msg = new StringBuilder();
        final Class<?> clazz = object.getClass();
        try {
            msg.append("Unable to read field '").append(fieldName).append("' from ").append(object)
                    .append(".  Known fields are:");
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                msg.append("\n").append(c).append(":");
                for (Field f : c.getDeclaredFields()) {
                    msg.append("\n  ").append(f);
                }
            }
            final Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(object);
            return value;
        } catch (Exception e) {
            throw new AssertionError(msg.toString(), e);
        }
    }
}
