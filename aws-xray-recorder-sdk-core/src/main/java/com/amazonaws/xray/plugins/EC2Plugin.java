/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazonaws.xray.plugins;

import com.amazonaws.xray.entities.AWSLogReference;
import com.amazonaws.xray.entities.StringValidator;
import com.amazonaws.xray.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A plugin, for use with the {@code AWSXRayRecorderBuilder} class, which will add EC2 instance information to segments generated
 * by the built {@code AWSXRayRecorder} instance.
 * 
 * @see com.amazonaws.xray.AWSXRayRecorderBuilder#withPlugin(Plugin)
 *
 */
public class EC2Plugin implements Plugin {
    public static final String ORIGIN = "AWS::EC2::Instance";

    private static final Log logger = LogFactory.getLog(EC2Plugin.class);

    private static final String SERVICE_NAME = "ec2";

    private static final String LOG_CONFIGS = "log_configs";
    private static final String LOG_GROUP_NAME = "log_group_name";

    private static final String WINDOWS_PROGRAM_DATA = "ProgramData";
    private static final String WINDOWS_PATH = "\\Amazon\\AmazonCloudWatchAgent\\log-config.json";

    private static final String LINUX_ROOT = "/";
    private static final String LINUX_PATH = "opt/aws/amazon-cloudwatch-agent/etc/log-config.json";

    private final Map<String, @Nullable Object> runtimeContext;

    private final Set<AWSLogReference> logReferences;

    private final FileSystem fs;

    private final Map<EC2MetadataFetcher.EC2Metadata, String> metadata;

    public EC2Plugin() {
        this(FileSystems.getDefault(), new EC2MetadataFetcher());
    }

    public EC2Plugin(FileSystem fs, EC2MetadataFetcher metadataFetcher) {
        this.fs = fs;
        metadata = metadataFetcher.fetch();
        runtimeContext = new LinkedHashMap<>();
        logReferences = new HashSet<>();
    }

    @Override
    public boolean isEnabled() {
        return metadata.containsKey(EC2MetadataFetcher.EC2Metadata.INSTANCE_ID);
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    /**
     * Reads EC2 provided metadata to include it in trace document
     */
    public void populateRuntimeContext() {
        runtimeContext.put("instance_id", metadata.get(EC2MetadataFetcher.EC2Metadata.INSTANCE_ID));
        runtimeContext.put("availability_zone", metadata.get(EC2MetadataFetcher.EC2Metadata.AVAILABILITY_ZONE));
        runtimeContext.put("instance_size", metadata.get(EC2MetadataFetcher.EC2Metadata.INSTANCE_TYPE));
        runtimeContext.put("ami_id", metadata.get(EC2MetadataFetcher.EC2Metadata.AMI_ID));
    }

    @Override
    public Map<String, @Nullable Object> getRuntimeContext() {
        populateRuntimeContext();
        return runtimeContext;
    }

    /**
     * Reads the log group configuration file generated by the CloudWatch Agent to discover all log groups being used on this
     * instance and populates log reference set with them to be included in trace documents.
     */
    public void populateLogReferences() {
        String filePath = null;
        String programData = System.getenv(WINDOWS_PROGRAM_DATA);

        if (StringValidator.isNullOrBlank(programData)) {
            for (Path root : fs.getRootDirectories()) {
                if (root.toString().equals(LINUX_ROOT)) {
                    filePath = LINUX_ROOT + LINUX_PATH;
                    break;
                }
            }
        } else {
            filePath = programData + WINDOWS_PATH;
        }

        if (filePath == null) {
            logger.warn("X-Ray could not recognize the file system in use. Expected file system to be Linux or Windows based.");
            return;
        }

        try {
            JsonNode logConfigs = JsonUtils.getNodeFromJsonFile(filePath, LOG_CONFIGS);
            List<String> logGroups = JsonUtils.getMatchingListFromJsonArrayNode(logConfigs, LOG_GROUP_NAME);

            for (String logGroup : logGroups) {
                AWSLogReference logReference = new AWSLogReference();
                logReference.setLogGroup(logGroup);
                logReferences.add(logReference);
            }
        } catch (IOException e) {
            logger.warn("CloudWatch Agent log configuration file not found at " + filePath + ". Install the CloudWatch Agent "
                        + "on this instance to record log references in X-Ray.");
        } catch (RuntimeException e) {
            logger.warn("An unexpected exception occurred while reading CloudWatch agent log configuration file at " + filePath
                        + ":\n", e);
        }
    }

    /**
     *
     * @return Set of AWS log references used by CloudWatch agent. The ARN of these log references is not available at this time.
     */
    @Override
    public Set<AWSLogReference> getLogReferences() {
        if (logReferences.isEmpty()) {
            populateLogReferences();
        }

        return logReferences;
    }

    @Override
    public String getOrigin() {
        return ORIGIN;
    }

    /**
     * Determine equality of plugins using origin to uniquely identify them
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof Plugin)) { return false; }
        return this.getOrigin().equals(((Plugin) o).getOrigin());
    }

    /**
     * Hash plugin object using origin to uniquely identify them
     */
    @Override
    public int hashCode() {
        return this.getOrigin().hashCode();
    }
}
