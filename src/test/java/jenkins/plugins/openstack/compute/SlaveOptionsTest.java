package jenkins.plugins.openstack.compute;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * @author ogondza.
 */
public class SlaveOptionsTest {

    /**
     * Reusable options instance guaranteed not to collide with defaults
     */
    public static final SlaveOptions CUSTOM = new SlaveOptions(
            JCloudsCloud.BootSource.VOLUMESNAPSHOT, "img", "hw", "nw", "ud", 1, "pool", "sg", "az", 1, null, 10, "jvmo", "fsRoot", "cid", JCloudsCloud.SlaveType.JNLP, 1
    );

    @Test // instanceCap is a subject of different overriding rules
    public void defaultOverrides() {
        SlaveOptions unmodified = CUSTOM.override(SlaveOptions.empty());

        assertEquals(JCloudsCloud.BootSource.VOLUMESNAPSHOT, unmodified.getBootSource());
        assertEquals("img", unmodified.getImageId());
        assertEquals("hw", unmodified.getHardwareId());
        assertEquals("nw", unmodified.getNetworkId());
        assertEquals("ud", unmodified.getUserDataId());
        assertEquals(1, (int) unmodified.getInstanceCap());
        assertEquals("pool", unmodified.getFloatingIpPool());
        assertEquals("sg", unmodified.getSecurityGroups());
        assertEquals("az", unmodified.getAvailabilityZone());
        assertEquals(1, (int) unmodified.getStartTimeout());
        assertEquals(10, (int) unmodified.getNumExecutors());
        assertEquals("jvmo", unmodified.getJvmOptions());
        assertEquals("fsRoot", unmodified.getFsRoot());
        assertEquals(null, unmodified.getKeyPairName());
        assertEquals("cid", unmodified.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.JNLP, unmodified.getSlaveType());
        assertEquals(1, (int) unmodified.getRetentionTime());

        SlaveOptions override = SlaveOptions.builder()
                .bootSource(JCloudsCloud.BootSource.IMAGE)
                .imageId("IMG")
                .hardwareId("HW")
                .networkId("NW")
                .userDataId("UD")
                .instanceCap(42)
                .floatingIpPool("POOL")
                .securityGroups("SG")
                .availabilityZone("AZ")
                .startTimeout(4)
                .numExecutors(2)
                .jvmOptions("JVMO")
                .fsRoot("FSROOT")
                .keyPairName("KPN")
                .credentialsId(null)
                .slaveType(JCloudsCloud.SlaveType.SSH)
                .retentionTime(3)
                .build()
        ;
        SlaveOptions overridden = CUSTOM.override(override);

        assertEquals(JCloudsCloud.BootSource.IMAGE, overridden.getBootSource());
        assertEquals("IMG", overridden.getImageId());
        assertEquals("HW", overridden.getHardwareId());
        assertEquals("NW", overridden.getNetworkId());
        assertEquals("UD", overridden.getUserDataId());
        assertEquals(42, (int) overridden.getInstanceCap());
        assertEquals("POOL", overridden.getFloatingIpPool());
        assertEquals("SG", overridden.getSecurityGroups());
        assertEquals("AZ", overridden.getAvailabilityZone());
        assertEquals(4, (int) overridden.getStartTimeout());
        assertEquals(2, (int) overridden.getNumExecutors());
        assertEquals("JVMO", overridden.getJvmOptions());
        assertEquals("FSROOT", overridden.getFsRoot());
        assertEquals("KPN", overridden.getKeyPairName());
        assertEquals("cid", overridden.getCredentialsId());
        assertEquals(JCloudsCloud.SlaveType.SSH, overridden.getSlaveType());
        assertEquals(3, (int) overridden.getRetentionTime());
    }

    @Test
    public void eraseDefaults() {
        SlaveOptions defaults = SlaveOptions.builder().imageId("img").hardwareId("hw").networkId(null).floatingIpPool("a").build();
        SlaveOptions configured = SlaveOptions.builder().imageId("IMG").hardwareId("hw").networkId("MW").floatingIpPool("A").build();

        SlaveOptions actual = configured.eraseDefaults(defaults);

        SlaveOptions expected = SlaveOptions.builder().imageId("IMG").hardwareId(null).networkId("MW").floatingIpPool("A").build();
        assertEquals(expected, actual);
        assertEquals(configured, defaults.override(actual));
    }

    @Test
    public void eraseDefaultsOnlyErasesImageIdOrBootSourceIfBothMatchDefault() {
        SlaveOptions defaults = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.IMAGE).imageId("img").hardwareId("hw").availabilityZone("defaults").build();
        SlaveOptions bothSame = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.IMAGE).imageId("img").hardwareId("hw").availabilityZone("bothSame").build();
        SlaveOptions differentImage = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.IMAGE).imageId("IMG").hardwareId("hw").availabilityZone("differentImage").build();
        SlaveOptions differentBS = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.VOLUMESNAPSHOT).imageId("img").hardwareId("hw").availabilityZone("differentBS").build();
        SlaveOptions bothDifferent = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.VOLUMESNAPSHOT).imageId("IMG").hardwareId("hw").availabilityZone("bothDifferent").build();

        SlaveOptions actualBothSame = bothSame.eraseDefaults(defaults);
        SlaveOptions actualDifferentImage = differentImage.eraseDefaults(defaults);
        SlaveOptions actualDifferentBS = differentBS.eraseDefaults(defaults);
        SlaveOptions actualBothDifferent = bothDifferent.eraseDefaults(defaults);

        SlaveOptions expectedBothSame = SlaveOptions.builder().availabilityZone("bothSame").build();
        SlaveOptions expectedDifferentImage = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.IMAGE).imageId("IMG").availabilityZone("differentImage").build();
        SlaveOptions expectedDifferentBS = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.VOLUMESNAPSHOT).imageId("img").availabilityZone("differentBS").build();
        SlaveOptions expectedBothDifferent = SlaveOptions.builder().bootSource(JCloudsCloud.BootSource.VOLUMESNAPSHOT).imageId("IMG").availabilityZone("bothDifferent").build();
        assertEquals(actualBothSame, expectedBothSame);
        assertEquals(actualDifferentImage, expectedDifferentImage);
        assertEquals(actualDifferentBS, expectedDifferentBS);
        assertEquals(actualBothDifferent, expectedBothDifferent);
    }

    @Test
    public void emptyStrings() {
        SlaveOptions nulls = SlaveOptions.empty();
        SlaveOptions emptyStrings = new SlaveOptions(
                null, "", "", "", "", null, "", "", "", null, "", null, "", "", "", null, null
        );
        SlaveOptions emptyBuilt = SlaveOptions.builder()
                .imageId("")
                .hardwareId("")
                .networkId("")
                .userDataId("")
                .floatingIpPool("")
                .securityGroups("")
                .availabilityZone("")
                .jvmOptions("")
                .fsRoot("")
                .keyPairName("")
                .credentialsId("")
                .build()
        ;
        assertEquals(nulls, emptyStrings);
        assertEquals(nulls, emptyBuilt);

        assertEquals(null, emptyStrings.getImageId());
        assertEquals(null, emptyStrings.getHardwareId());
        assertEquals(null, emptyStrings.getNetworkId());
        assertEquals(null, emptyStrings.getUserDataId());
        assertEquals(null, emptyStrings.getSecurityGroups());
        assertEquals(null, emptyStrings.getAvailabilityZone());
        assertEquals(null, emptyStrings.getJvmOptions());
        assertEquals(null, emptyStrings.getFsRoot());
        assertEquals(null, emptyStrings.getKeyPairName());
        assertEquals(null, emptyStrings.getCredentialsId());
    }

    @Test
    public void modifyThroughBuilder() {
        assertEquals(CUSTOM, CUSTOM.getBuilder().build());
    }
}
