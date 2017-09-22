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

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.plugins.openstack.PluginTestRule;
import jenkins.plugins.openstack.compute.SlaveOptions;
import jenkins.plugins.openstack.compute.SlaveOptionsDescriptor;
import jenkins.plugins.openstack.compute.internal.Openstack;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.openstack4j.api.OSClient;
import org.openstack4j.api.image.ImageService;
import org.openstack4j.model.image.Image;
import org.openstack4j.model.storage.block.VolumeSnapshot;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static jenkins.plugins.openstack.compute.SlaveOptionsDescriptorTest.hasState;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class BootSourceTest {
    private static final FormValidation VALIDATION_REQUIRED = FormValidation.error(hudson.util.Messages.FormValidation_ValidateRequired());

    public @Rule PluginTestRule j = new PluginTestRule();

    private BootSource.Image.Desc id;
    private BootSource.VolumeSnapshot.Desc vsd;

    @Before
    public void before() {
        id = (BootSource.Image.Desc) j.jenkins.getDescriptorOrDie(BootSource.Image.class);
        vsd = (BootSource.VolumeSnapshot.Desc) j.jenkins.getDescriptorOrDie(BootSource.VolumeSnapshot.class);
    }

    @Test
    public void doFillImageNameItemsPopulatesImageNamesNotIds() {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn("image-id");
        when(image.getName()).thenReturn("image-name");

        Openstack os = j.fakeOpenstackFactory();
        doReturn(Collections.singletonMap("image-name", Collections.singletonList(image))).when(os).getImages();

        ListBoxModel list = id.doFillNameItems("", "OSurl", "OSid", "OSpwd", "OSzone");
        assertEquals(3, list.size());
        ListBoxModel.Option item = list.get(1);
        assertEquals(image.getName(), item.name);
        assertEquals(image.getName(), item.value);
    }

    @Test
    public void doFillSnapshotNameItemsPopulatesVolumeSnapshotNames() {
        VolumeSnapshot volumeSnapshot = mock(VolumeSnapshot.class);
        when(volumeSnapshot.getId()).thenReturn("vs-id");
        when(volumeSnapshot.getName()).thenReturn("vs-name");
        final Collection<VolumeSnapshot> justVolumeSnapshot = Collections.singletonList(volumeSnapshot);

        Openstack os = j.fakeOpenstackFactory();
        when(os.getVolumeSnapshots()).thenReturn(Collections.singletonMap("vs-name", justVolumeSnapshot));

        ListBoxModel list = vsd.doFillNameItems("existing-vs-name", "OSurl", "OSid", "OSpwd", "OSzone");
        assertEquals(3, list.size());
        assertEquals("First menu entry is 'nothing selected'", "", list.get(0).value);
        assertEquals("Second menu entry is the VS OpenStack can see", "vs-name", list.get(1).name);
        assertEquals("Second menu entry is the VS OpenStack can see", "vs-name", list.get(1).value);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).name);
        assertEquals("Third menu entry is the existing value", "existing-vs-name", list.get(2).value);
    }

    @Test @Issue("JENKINS-29993")
    public void doFillImageIdItemsAcceptsNullAsImageName() {
        Image image = mock(Image.class);
        when(image.getId()).thenReturn("image-id");
        when(image.getName()).thenReturn(null);

        OSClient osClient = mock(OSClient.class);
        ImageService imageService = mock(ImageService.class);
        when(osClient.images()).thenReturn(imageService);
        doReturn(Collections.singletonList(image)).when(imageService).listAll();

        j.fakeOpenstackFactory(new Openstack(osClient));

        ListBoxModel list = id.doFillNameItems("", "OSurl", "OSid", "OSpwd", "OSzone");
        assertThat(list.get(0).name, list, Matchers.<ListBoxModel.Option>iterableWithSize(2));
        assertEquals(2, list.size());
        ListBoxModel.Option item = list.get(1);
        assertEquals("image-id", item.name);
        assertEquals("image-id", item.value);

        verify(imageService).listAll();
        verifyNoMoreInteractions(imageService);
    }

    @Test
    public void doCheckImageIdWhenNoValueSet() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";

        final FormValidation actual = id.doCheckName("", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(VALIDATION_REQUIRED));
    }

    @Test
    public void doCheckImageIdWhenImageIsNotFoundInOpenstack() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        when(os.getImageIdsFor("imageNotFound")).thenReturn(Collections.emptyList());
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.error("Not found");

        final FormValidation actual = id.doCheckName("imageNotFound", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenOneImageIsFound() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        when(os.getImageIdsFor("imageFound")).thenReturn(Collections.singletonList("imageFoundId"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = id.doCheckName("imageFound", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat(actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenMultipleImagesAreFoundForTheName() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        when(os.getImageIdsFor("imageAmbiguous")).thenReturn(Arrays.asList("imageAmbiguousId1", "imageAmbiguousId2"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.warning("Multiple matching results");

        final FormValidation actual = id.doCheckName("imageAmbiguous", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat("imageAmbiguous", actual, hasState(expected));
    }

    @Test
    public void doCheckImageIdWhenOneVolumeSnapshotIsFound() throws Exception {
        final String urlC, urlT, idC, idT, credC, credT, zoneC, zoneT;
        urlC= urlT= idC= idT= credC= credT= zoneC= zoneT= "dummy";
        final Openstack os = mock(Openstack.class);
        when(os.getVolumeSnapshotIdsFor("vsFound")).thenReturn(Collections.singletonList("vsFoundId"));
        j.fakeOpenstackFactory(os);
        final FormValidation expected = FormValidation.ok();

        final FormValidation actual = vsd.doCheckName("vsFound", urlC, urlT, idC, idT, credC, credT, zoneC, zoneT);
        assertThat("vsFound", actual, hasState(expected));
    }
}
