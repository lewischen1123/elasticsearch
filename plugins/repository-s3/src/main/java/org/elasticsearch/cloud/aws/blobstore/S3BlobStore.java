/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cloud.aws.blobstore;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.CreateBucketRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest.KeyVersion;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.StorageClass;
import org.elasticsearch.cloud.aws.util.SocketAccess;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.blobstore.BlobContainer;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.blobstore.BlobStoreException;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Locale;

public class S3BlobStore extends AbstractComponent implements BlobStore {

    private final AmazonS3 client;

    private final String bucket;

    private final ByteSizeValue bufferSize;

    private final boolean serverSideEncryption;

    private final int numberOfRetries;

    private final CannedAccessControlList cannedACL;

    private final StorageClass storageClass;

    public S3BlobStore(Settings settings, AmazonS3 client, String bucket, boolean serverSideEncryption,
                       ByteSizeValue bufferSize, String cannedACL, String storageClass) {
        super(settings);
        this.client = client;
        this.bucket = bucket;
        this.serverSideEncryption = serverSideEncryption;
        this.bufferSize = bufferSize;
        this.cannedACL = initCannedACL(cannedACL);
        this.numberOfRetries = 0;
        this.storageClass = initStorageClass(storageClass);

        // Note: the method client.doesBucketExist() may return 'true' is the bucket exists
        // but we don't have access to it (ie, 403 Forbidden response code)
        // Also, if invalid security credentials are used to execute this method, the
        // client is not able to distinguish between bucket permission errors and
        // invalid credential errors, and this method could return an incorrect result.
        SocketAccess.doPrivilegedVoid(() -> {
            if (client.doesBucketExist(bucket) == false) {
                throw new IllegalArgumentException("The bucket [" + bucket + "] does not exist. Please create it before " +
                                                   " creating an s3 snapshot repository backed by it.");
            }
        });
    }

    @Override
    public String toString() {
        return bucket;
    }

    public AmazonS3 client() {
        return client;
    }

    public String bucket() {
        return bucket;
    }

    public boolean serverSideEncryption() {
        return serverSideEncryption;
    }

    public int bufferSizeInBytes() {
        return bufferSize.bytesAsInt();
    }

    @Override
    public BlobContainer blobContainer(BlobPath path) {
        return new S3BlobContainer(path, this);
    }

    @Override
    public void delete(BlobPath path) {
        AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
            ObjectListing prevListing = null;
            //From http://docs.amazonwebservices.com/AmazonS3/latest/dev/DeletingMultipleObjectsUsingJava.html
            //we can do at most 1K objects per delete
            //We don't know the bucket name until first object listing
            DeleteObjectsRequest multiObjectDeleteRequest = null;
            ArrayList<KeyVersion> keys = new ArrayList<KeyVersion>();
            while (true) {
                ObjectListing list;
                if (prevListing != null) {
                    list = client.listNextBatchOfObjects(prevListing);
                } else {
                    list = client.listObjects(bucket, path.buildAsString());
                    multiObjectDeleteRequest = new DeleteObjectsRequest(list.getBucketName());
                }
                for (S3ObjectSummary summary : list.getObjectSummaries()) {
                    keys.add(new KeyVersion(summary.getKey()));
                    //Every 500 objects batch the delete request
                    if (keys.size() > 500) {
                        multiObjectDeleteRequest.setKeys(keys);
                        client.deleteObjects(multiObjectDeleteRequest);
                        multiObjectDeleteRequest = new DeleteObjectsRequest(list.getBucketName());
                        keys.clear();
                    }
                }
                if (list.isTruncated()) {
                    prevListing = list;
                } else {
                    break;
                }
            }
            if (!keys.isEmpty()) {
                multiObjectDeleteRequest.setKeys(keys);
                client.deleteObjects(multiObjectDeleteRequest);
            }
            return null;
        });
    }

    protected boolean shouldRetry(AmazonClientException e) {
        if (e instanceof AmazonS3Exception) {
            AmazonS3Exception s3e = (AmazonS3Exception) e;
            if (s3e.getStatusCode() == 400 && "RequestTimeout".equals(s3e.getErrorCode())) {
                return true;
            }
        }
        return e.isRetryable();
    }

    @Override
    public void close() {
    }

    public CannedAccessControlList getCannedACL() {
        return cannedACL;
    }

    public StorageClass getStorageClass() { return storageClass; }

    public static StorageClass initStorageClass(String storageClass) {
        if (storageClass == null || storageClass.equals("")) {
            return StorageClass.Standard;
        }

        try {
            StorageClass _storageClass = StorageClass.fromValue(storageClass.toUpperCase(Locale.ENGLISH));
            if (_storageClass.equals(StorageClass.Glacier)) {
                throw new BlobStoreException("Glacier storage class is not supported");
            }

            return _storageClass;
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new BlobStoreException("`" + storageClass + "` is not a valid S3 Storage Class.");
        }
    }

    /**
     * Constructs canned acl from string
     */
    public static CannedAccessControlList initCannedACL(String cannedACL) {
        if (cannedACL == null || cannedACL.equals("")) {
            return CannedAccessControlList.Private;
        }

        for (CannedAccessControlList cur : CannedAccessControlList.values()) {
            if (cur.toString().equalsIgnoreCase(cannedACL)) {
                return cur;
            }
        }

        throw new BlobStoreException("cannedACL is not valid: [" + cannedACL + "]");
    }
}
