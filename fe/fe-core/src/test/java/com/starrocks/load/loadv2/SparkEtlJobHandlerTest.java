// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/load/loadv2/SparkEtlJobHandlerTest.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.load.loadv2;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.BrokerDesc;
import com.starrocks.catalog.BrokerMgr;
import com.starrocks.catalog.FsBroker;
import com.starrocks.catalog.SparkResource;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.LoadException;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.TimeoutException;
import com.starrocks.common.util.BrokerUtil;
import com.starrocks.common.util.CommandResult;
import com.starrocks.common.util.Util;
import com.starrocks.load.EtlStatus;
import com.starrocks.load.loadv2.etl.EtlJobConfig;
import com.starrocks.rpc.ThriftConnectionPool;
import com.starrocks.rpc.ThriftRPCRequestExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TBrokerFileStatus;
import com.starrocks.thrift.TBrokerListResponse;
import com.starrocks.thrift.TBrokerOperationStatus;
import com.starrocks.thrift.TBrokerOperationStatusCode;
import com.starrocks.thrift.TEtlState;
import com.starrocks.thrift.TFileBrokerService;
import com.starrocks.thrift.TNetworkAddress;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.apache.spark.launcher.SparkLauncher;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class SparkEtlJobHandlerTest {
    private long loadJobId;
    private String label;
    private String resourceName;
    private String broker;
    private long pendingTaskId;
    private String appId;
    private String etlOutputPath;
    private String trackingUrl;
    private String dppVersion;
    private String remoteArchivePath;
    private SparkRepository.SparkArchive archive;

    private final String runningReport = "Application Report :\n" +
            "Application-Id : application_15888888888_0088\n" +
            "Application-Name : label0\n" +
            "Application-Type : SPARK-2.4.1\n" +
            "User : test\n" +
            "Queue : test-queue\n" +
            "Start-Time : 1597654469958\n" +
            "Finish-Time : 0\n" +
            "Progress : 50%\n" +
            "State : RUNNING\n" +
            "Final-State : UNDEFINED\n" +
            "Tracking-URL : http://127.0.0.1:8080/proxy/application_1586619723848_0088/\n" +
            "RPC Port : 40236\n" +
            "AM Host : host-name";

    private final String failedReport = "Application Report :\n" +
            "Application-Id : application_15888888888_0088\n" +
            "Application-Name : label0\n" +
            "Application-Type : SPARK-2.4.1\n" +
            "User : test\n" +
            "Queue : test-queue\n" +
            "Start-Time : 1597654469958\n" +
            "Finish-Time : 1597654801939\n" +
            "Progress : 100%\n" +
            "State : FINISHED\n" +
            "Final-State : FAILED\n" +
            "Tracking-URL : http://127.0.0.1:8080/proxy/application_1586619723848_0088/\n" +
            "RPC Port : 40236\n" +
            "AM Host : host-name";

    private final String finishReport = "Application Report :\n" +
            "Application-Id : application_15888888888_0088\n" +
            "Application-Name : label0\n" +
            "Application-Type : SPARK-2.4.1\n" +
            "User : test\n" +
            "Queue : test-queue\n" +
            "Start-Time : 1597654469958\n" +
            "Finish-Time : 1597654801939\n" +
            "Progress : 100%\n" +
            "State : FINISHED\n" +
            "Final-State : SUCCEEDED\n" +
            "Tracking-URL : http://127.0.0.1:8080/proxy/application_1586619723848_0088/\n" +
            "RPC Port : 40236\n" +
            "AM Host : host-name";

    @BeforeEach
    public void setUp() {
        FeConstants.runningUnitTest = true;
        loadJobId = 0L;
        label = "label0";
        resourceName = "spark0";
        broker = "broker0";
        pendingTaskId = 3L;
        appId = "application_15888888888_0088";
        etlOutputPath = "hdfs://127.0.0.1:10000/tmp/starrocks/100/label/101";
        trackingUrl = "http://127.0.0.1:8080/proxy/application_1586619723848_0088/";
        dppVersion = Config.spark_dpp_version;
        remoteArchivePath = etlOutputPath + "/__repository__/__archive_" + dppVersion;
        archive = new SparkRepository.SparkArchive(remoteArchivePath, dppVersion);
        archive.libraries.add(new SparkRepository
                .SparkLibrary("", "", SparkRepository.SparkLibrary.LibType.DPP, 0L));
        archive.libraries.add(new SparkRepository
                .SparkLibrary("", "", SparkRepository.SparkLibrary.LibType.SPARK2X, 0L));
    }

    @Test
    public void testSubmitEtlJob(@Mocked BrokerUtil brokerUtil, @Mocked SparkLauncher launcher,
                                 @Injectable Process process,
                                 @Mocked SparkLoadAppHandle handle) throws IOException, LoadException {
        new Expectations() {
            {
                launcher.launch();
                result = process;
                handle.getAppId();
                result = appId;
                handle.getState();
                result = SparkLoadAppHandle.State.RUNNING;
            }
        };

        EtlJobConfig etlJobConfig = new EtlJobConfig(Maps.newHashMap(), etlOutputPath, label, null);
        SparkResource resource = new SparkResource(resourceName);
        new Expectations(resource) {
            {
                resource.prepareArchive();
                result = archive;
            }
        };

        Map<String, String> sparkConfigs = resource.getSparkConfigs();
        sparkConfigs.put("spark.master", "yarn");
        sparkConfigs.put("spark.submit.deployMode", "cluster");
        sparkConfigs.put("spark.hadoop.yarn.resourcemanager.address", "127.0.0.1:9999");
        BrokerDesc brokerDesc = new BrokerDesc(broker, Maps.newHashMap());
        SparkPendingTaskAttachment attachment = new SparkPendingTaskAttachment(pendingTaskId);
        SparkEtlJobHandler handler = new SparkEtlJobHandler();
        long sparkLoadSubmitTimeout = Config.spark_load_submit_timeout_second;
        handler.submitEtlJob(loadJobId, label, etlJobConfig, resource, brokerDesc, handle, attachment, sparkLoadSubmitTimeout);

        // check submit etl job success
        Assertions.assertEquals(appId, attachment.getAppId());
    }

    @Test
    public void testSubmitEtlJobFailed(@Mocked BrokerUtil brokerUtil, @Mocked SparkLauncher launcher,
                                       @Injectable Process process,
                                       @Mocked SparkLoadAppHandle handle) {
        assertThrows(LoadException.class, () -> {
            new Expectations() {
                {
                    launcher.launch();
                    result = process;
                    handle.getAppId();
                    result = appId;
                    handle.getState();
                    result = SparkLoadAppHandle.State.FAILED;
                }
            };

            EtlJobConfig etlJobConfig = new EtlJobConfig(Maps.newHashMap(), etlOutputPath, label, null);
            SparkResource resource = new SparkResource(resourceName);
            new Expectations(resource) {
                {
                    resource.prepareArchive();
                    result = archive;
                }
            };

            Map<String, String> sparkConfigs = resource.getSparkConfigs();
            sparkConfigs.put("spark.master", "yarn");
            sparkConfigs.put("spark.submit.deployMode", "cluster");
            sparkConfigs.put("spark.hadoop.yarn.resourcemanager.address", "127.0.0.1:9999");
            BrokerDesc brokerDesc = new BrokerDesc(broker, Maps.newHashMap());
            SparkPendingTaskAttachment attachment = new SparkPendingTaskAttachment(pendingTaskId);
            SparkEtlJobHandler handler = new SparkEtlJobHandler();
            long sparkLoadSubmitTimeout = Config.spark_load_submit_timeout_second;
            handler.submitEtlJob(loadJobId, label, etlJobConfig, resource, brokerDesc, handle, attachment,
                    sparkLoadSubmitTimeout);
        });
    }

    @Test
    public void testGetEtlJobStatus(@Mocked BrokerUtil brokerUtil, @Mocked Util util,
                                    @Mocked CommandResult commandResult,
                                    @Mocked SparkYarnConfigFiles sparkYarnConfigFiles,
                                    @Mocked SparkLoadAppHandle handle)
            throws IOException, StarRocksException {

        new Expectations() {
            {
                sparkYarnConfigFiles.prepare();
                sparkYarnConfigFiles.getConfigDir();
                result = "./yarn_config";

                commandResult.getReturnCode();
                result = 0;
                commandResult.getStdout();
                returns(runningReport, runningReport, failedReport, failedReport, finishReport, finishReport);

                handle.getUrl();
                result = trackingUrl;
            }
        };

        new Expectations() {
            {
                Util.executeCommand(anyString, (String[]) any, anyLong);
                minTimes = 0;
                result = commandResult;

                BrokerUtil.readFile(anyString, (BrokerDesc) any);
                result = "{'normal_rows': 10, 'abnormal_rows': 0, 'failed_reason': 'etl job failed'}";
            }
        };

        SparkResource resource = new SparkResource(resourceName);
        Map<String, String> sparkConfigs = resource.getSparkConfigs();
        sparkConfigs.put("spark.master", "yarn");
        sparkConfigs.put("spark.submit.deployMode", "cluster");
        sparkConfigs.put("spark.hadoop.yarn.resourcemanager.address", "127.0.0.1:9999");
        new Expectations(resource) {
            {
                resource.getYarnClientPath();
                result = Config.yarn_client_path;
            }
        };

        BrokerDesc brokerDesc = new BrokerDesc(broker, Maps.newHashMap());
        SparkEtlJobHandler handler = new SparkEtlJobHandler();

        // running
        EtlStatus status = handler.getEtlJobStatus(handle, appId, loadJobId, etlOutputPath, resource, brokerDesc);
        Assertions.assertEquals(TEtlState.RUNNING, status.getState());
        Assertions.assertEquals(50, status.getProgress());
        Assertions.assertEquals(trackingUrl, status.getTrackingUrl());

        // yarn finished and spark failed
        status = handler.getEtlJobStatus(handle, appId, loadJobId, etlOutputPath, resource, brokerDesc);
        Assertions.assertEquals(TEtlState.CANCELLED, status.getState());
        Assertions.assertEquals(100, status.getProgress());
        Assertions.assertEquals("etl job failed", status.getDppResult().failedReason);

        // finished
        status = handler.getEtlJobStatus(handle, appId, loadJobId, etlOutputPath, resource, brokerDesc);
        Assertions.assertEquals(TEtlState.FINISHED, status.getState());
        Assertions.assertEquals(100, status.getProgress());
        Assertions.assertEquals(trackingUrl, status.getTrackingUrl());
        Assertions.assertEquals(10, status.getDppResult().normalRows);
        Assertions.assertEquals(0, status.getDppResult().abnormalRows);
    }

    @Test
    public void testGetEtlJobStatusTimeout(@Mocked BrokerUtil brokerUtil, @Mocked Util util,
                                           @Mocked SparkYarnConfigFiles sparkYarnConfigFiles,
                                           @Mocked SparkLoadAppHandle handle) {
        assertThrows(TimeoutException.class, () -> {

            new Expectations() {
                {
                    sparkYarnConfigFiles.prepare();
                    sparkYarnConfigFiles.getConfigDir();
                    result = "./yarn_config";

                    Util.executeCommand(anyString, (String[]) any, anyLong);
                    minTimes = 0;
                    result = new TimeoutException("get spark etl job status timeout");
                }
            };

            SparkResource resource = new SparkResource(resourceName);
            Map<String, String> sparkConfigs = resource.getSparkConfigs();
            sparkConfigs.put("spark.master", "yarn");
            sparkConfigs.put("spark.submit.deployMode", "cluster");
            sparkConfigs.put("spark.hadoop.yarn.resourcemanager.address", "127.0.0.1:9999");
            new Expectations(resource) {
                {
                    resource.getYarnClientPath();
                    result = Config.yarn_client_path;
                }
            };

            BrokerDesc brokerDesc = new BrokerDesc(broker, Maps.newHashMap());
            SparkEtlJobHandler handler = new SparkEtlJobHandler();
            handler.getEtlJobStatus(handle, appId, loadJobId, etlOutputPath, resource, brokerDesc);
        });
    }

    @Test
    public void testGetEtlJobStatusFailed(@Mocked Util util, @Mocked CommandResult commandResult,
                                          @Mocked SparkYarnConfigFiles sparkYarnConfigFiles,
                                          @Mocked SparkLoadAppHandle handle) {
        assertThrows(LoadException.class, () -> {

            new Expectations() {
                {
                    sparkYarnConfigFiles.prepare();
                    sparkYarnConfigFiles.getConfigDir();
                    result = "./yarn_config";

                    commandResult.getReturnCode();
                    result = -1;
                }
            };

            new Expectations() {
                {
                    Util.executeCommand(anyString, (String[]) any, anyLong);
                    minTimes = 0;
                    result = commandResult;
                }
            };

            SparkResource resource = new SparkResource(resourceName);
            Map<String, String> sparkConfigs = resource.getSparkConfigs();
            sparkConfigs.put("spark.master", "yarn");
            sparkConfigs.put("spark.submit.deployMode", "cluster");
            sparkConfigs.put("spark.hadoop.yarn.resourcemanager.address", "127.0.0.1:9999");
            new Expectations(resource) {
                {
                    resource.getYarnClientPath();
                    result = Config.yarn_client_path;
                }
            };

            BrokerDesc brokerDesc = new BrokerDesc(broker, Maps.newHashMap());
            SparkEtlJobHandler handler = new SparkEtlJobHandler();

            // yarn application status failed
            handler.getEtlJobStatus(null, appId, loadJobId, etlOutputPath, resource, brokerDesc);
        });
    }

    @Test
    public void testKillEtlJob(@Mocked Util util, @Mocked CommandResult commandResult,
                               @Mocked SparkYarnConfigFiles sparkYarnConfigFiles) throws IOException,
            StarRocksException {
        new Expectations() {
            {
                sparkYarnConfigFiles.prepare();
                sparkYarnConfigFiles.getConfigDir();
                result = "./yarn_config";
            }
        };

        new Expectations() {
            {
                commandResult.getReturnCode();
                result = 0;
            }
        };

        new Expectations() {
            {
                Util.executeCommand(anyString, (String[]) any, anyLong);
                minTimes = 0;
                result = commandResult;
            }
        };

        SparkResource resource = new SparkResource(resourceName);
        Map<String, String> sparkConfigs = resource.getSparkConfigs();
        sparkConfigs.put("spark.master", "yarn");
        sparkConfigs.put("spark.submit.deployMode", "cluster");
        sparkConfigs.put("spark.hadoop.yarn.resourcemanager.address", "127.0.0.1:9999");
        new Expectations(resource) {
            {
                resource.getYarnClientPath();
                result = Config.yarn_client_path;
            }
        };

        SparkEtlJobHandler handler = new SparkEtlJobHandler();
        try {
            handler.killEtlJob(null, appId, loadJobId, resource);
        } catch (Exception e) {
            Assertions.fail(e.getMessage());
        }
    }

    @Test
    public void testGetEtlFilePaths(@Mocked TFileBrokerService.Client client, @Mocked GlobalStateMgr globalStateMgr,
                                    @Injectable BrokerMgr brokerMgr) throws Exception {
        // list response
        TBrokerListResponse response = new TBrokerListResponse();
        TBrokerOperationStatus status = new TBrokerOperationStatus();
        status.statusCode = TBrokerOperationStatusCode.OK;
        response.opStatus = status;
        List<TBrokerFileStatus> files = Lists.newArrayList();
        String filePath = "hdfs://127.0.0.1:10000/starrocks/jobs/1/label6/9/label6.10.11.12.0.666666.parquet";
        files.add(new TBrokerFileStatus(filePath, false, 10, false));
        response.files = files;

        FsBroker fsBroker = new FsBroker("127.0.0.1", 99999);

        new MockUp<ThriftConnectionPool<TFileBrokerService.Client>>() {
            @Mock
            public TFileBrokerService.Client borrowObject(TNetworkAddress address, int timeoutMs) throws Exception {
                return client;
            }

            @Mock
            public void returnObject(TNetworkAddress address, TFileBrokerService.Client object) {
            }

            @Mock
            public void invalidateObject(TNetworkAddress address, TFileBrokerService.Client object) {
            }
        };

        new Expectations() {
            {
                globalStateMgr.getBrokerMgr();
                result = brokerMgr;
                brokerMgr.getBroker(anyString, anyString);
                result = fsBroker;
            }
        };

        try (MockedStatic<ThriftRPCRequestExecutor> thriftConnectionPoolMockedStatic =
                     Mockito.mockStatic(ThriftRPCRequestExecutor.class)) {
            thriftConnectionPoolMockedStatic.when(()
                            -> ThriftRPCRequestExecutor.call(Mockito.any(), Mockito.any(), Mockito.any()))
                    .thenReturn(response);

            BrokerDesc brokerDesc = new BrokerDesc(broker, Maps.newHashMap());
            SparkEtlJobHandler handler = new SparkEtlJobHandler();
            Map<String, Long> filePathToSize = handler.getEtlFilePaths(etlOutputPath, brokerDesc);
            Assertions.assertTrue(filePathToSize.containsKey(filePath));
            Assertions.assertEquals(10, (long) filePathToSize.get(filePath));
        }
    }

    @Test
    public void testDeleteEtlOutputPath(@Mocked BrokerUtil brokerUtil) throws StarRocksException {
        new Expectations() {
            {
                BrokerUtil.deletePath(etlOutputPath, (BrokerDesc) any);
                times = 1;
            }
        };

        BrokerDesc brokerDesc = new BrokerDesc(broker, Maps.newHashMap());
        SparkEtlJobHandler handler = new SparkEtlJobHandler();
        try {
            handler.deleteEtlOutputPath(etlOutputPath, brokerDesc);
        } catch (Exception e) {
            Assertions.fail(e.getMessage());
        }
    }
}
