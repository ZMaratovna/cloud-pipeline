/*
 * Copyright 2017-2020 EPAM Systems, Inc. (https://www.epam.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.pipeline.manager.datastorage.providers.nfs;

import com.epam.pipeline.entity.datastorage.MountType;
import com.epam.pipeline.entity.region.AwsRegion;
import com.epam.pipeline.entity.region.AzureRegion;
import com.epam.pipeline.entity.region.AzureRegionCredentials;
import com.epam.pipeline.manager.ObjectCreatorUtils;
import org.junit.Assert;
import org.junit.Test;

public class NFSHelperTest {

    private static final String TEST_PATH = "localhost";
    private static final String EMPTY_STRING = "";
    private static final String RESOURCE_GROUP = "rg";

    @Test
    public void getNFSMountOption() {
        String protocol = MountType.NFS.getProtocol();
        String result = NFSHelper.getNFSMountOption(new AwsRegion(), null, EMPTY_STRING, protocol);
        Assert.assertEquals(EMPTY_STRING, result);

        protocol = MountType.SMB.getProtocol();
        AzureRegion azureRegion = ObjectCreatorUtils.getDefaultAzureRegion(RESOURCE_GROUP, "account");
        AzureRegionCredentials credentials = ObjectCreatorUtils.getAzureCredentials("key");
        result = NFSHelper.getNFSMountOption(azureRegion, credentials, EMPTY_STRING, protocol);
        Assert.assertEquals("-o ,username=account,password=key", result);

        result = NFSHelper.getNFSMountOption(azureRegion, credentials, "options", protocol);
        Assert.assertEquals("-o options,username=account,password=key", result);

        azureRegion = ObjectCreatorUtils.getDefaultAzureRegion(RESOURCE_GROUP, null);
        result = NFSHelper.getNFSMountOption(azureRegion, null, EMPTY_STRING, protocol);
        Assert.assertEquals(EMPTY_STRING, result);

    }

    @Test
    public void formatNfsPath() {
        final String rightPath = "//samba.share/path";
        String result = NFSHelper.formatNfsPath(rightPath, "cifs");
        Assert.assertEquals(rightPath, result);

        final String unformattedPath = "samba.share/path";
        result = NFSHelper.formatNfsPath(unformattedPath, "cifs");
        //smb protocol -> should format with //
        Assert.assertEquals("//" + unformattedPath, result);

        //lustre protocol -> should add suffix
        result = NFSHelper.formatNfsPath(TEST_PATH, "lustre");
        Assert.assertEquals(TEST_PATH + "@tcp:/", result);

        //nfs protocol -> should add suffix
        result = NFSHelper.formatNfsPath(TEST_PATH, "nfs");
        Assert.assertEquals(TEST_PATH + ":/", result);
    }

    @Test
    public void getNfsRootPathTest() {
        String nfsRootPath = NFSHelper.getNfsRootPath(TEST_PATH + ":" + "directory");
        Assert.assertEquals(TEST_PATH + ":", nfsRootPath);
        nfsRootPath = NFSHelper.getNfsRootPath(TEST_PATH + ":" + "/directory");
        Assert.assertEquals(TEST_PATH + ":/", nfsRootPath);
        nfsRootPath = NFSHelper.getNfsRootPath(TEST_PATH + ":" + "/mnt/");
        Assert.assertEquals(TEST_PATH + ":/", nfsRootPath);
        nfsRootPath = NFSHelper.getNfsRootPath(TEST_PATH + ":" + "mnt/");
        Assert.assertEquals(TEST_PATH + ":", nfsRootPath);
        nfsRootPath = NFSHelper.getNfsRootPath(TEST_PATH + ":" + "/mnt/directory");
        Assert.assertEquals(TEST_PATH + ":/mnt/", nfsRootPath);
        nfsRootPath = NFSHelper.getNfsRootPath(TEST_PATH  + "/mnt/directory");
        Assert.assertEquals(TEST_PATH + "/mnt/", nfsRootPath);
    }

    @Test(expected = IllegalArgumentException.class)
    public void getNfsRootPathShouldFailIfPathInvalid() {
        NFSHelper.getNfsRootPath(TEST_PATH + ":");
    }
}