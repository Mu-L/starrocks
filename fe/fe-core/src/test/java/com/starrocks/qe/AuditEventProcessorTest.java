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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/qe/AuditEventProcessorTest.java

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

package com.starrocks.qe;

import com.starrocks.common.Config;
import com.starrocks.common.util.DigitalVersion;
import com.starrocks.plugin.AuditEvent;
import com.starrocks.plugin.AuditEvent.EventType;
import com.starrocks.plugin.PluginInfo;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class AuditEventProcessorTest {

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
    }

    @Test
    public void testAuditEvent() {
        AuditEvent event = new AuditEvent.AuditEventBuilder().setEventType(EventType.AFTER_QUERY)
                .setTimestamp(System.currentTimeMillis())
                .setClientIp("127.0.0.1")
                .setUser("user1")
                .setAuthorizedUser("user2")
                .setDb("db1")
                .setState("EOF")
                .setQueryTime(2000)
                .setScanBytes(100000)
                .setScanRows(200000)
                .setReturnRows(1)
                .setStmtId(1234)
                .setStmt("select * from tbl1")
                .setCatalog("catalog1").build();

        Assertions.assertEquals("127.0.0.1", event.clientIp);
        Assertions.assertEquals(200000, event.scanRows);
        Assertions.assertEquals("catalog1", event.catalog);
        Assertions.assertEquals("user2", event.authorizedUser);
    }

    @Test
    public void testAuditLogBuilderCONNECTION() throws IOException {
        try (AuditLogBuilder auditLogBuilder = new AuditLogBuilder()) {
            AuditEvent event = new AuditEvent.AuditEventBuilder().setEventType(EventType.CONNECTION)
                    .setTimestamp(System.currentTimeMillis())
                    .setClientIp("127.0.0.1")
                    .setUser("user1")
                    .setAuthorizedUser("user2")
                    .setDb("db1")
                    .setState("EOF")
                    .setQueryTime(2000)
                    .setScanBytes(100000)
                    .setScanRows(200000)
                    .setReturnRows(1)
                    .setStmtId(1234)
                    .setStmt("select * from tbl1").build();

            if (auditLogBuilder.eventFilter(event.type)) {
                auditLogBuilder.exec(event);
                Config.audit_log_json_format = true;
                auditLogBuilder.exec(event);
            }
            Assertions.assertEquals(EventType.CONNECTION,  event.type);
        }
    }

    @Test
    public void testAuditLogBuilderBigQuery() throws IOException {
        try (AuditLogBuilder auditLogBuilder = new AuditLogBuilder()) {
            AuditEvent event = new AuditEvent.AuditEventBuilder().setEventType(EventType.AFTER_QUERY)
                    .setTimestamp(System.currentTimeMillis())
                    .setClientIp("127.0.0.1")
                    .setUser("user1")
                    .setAuthorizedUser("user2")
                    .setDb("db1")
                    .setState("EOF")
                    .setQueryTime(2000)
                    .setScanBytes(100000)
                    .setScanRows(200000)
                    .setReturnRows(1)
                    .setStmtId(1234)
                    .setStmt("select * from tbl1")
                    .setBigQueryLogCPUSecondThreshold(5)
                    .setCpuCostNs(6 * 1000000000L).build();
            if (auditLogBuilder.eventFilter(event.type)) {
                auditLogBuilder.exec(event);
                Config.audit_log_json_format = true;
                auditLogBuilder.exec(event);
            }
            Assertions.assertEquals(6 * 1000000000L, event.cpuCostNs);
            Assertions.assertEquals(5, event.bigQueryLogCPUSecondThreshold);
        }
    }

    @Test
    public void testAuditLogBuilder() throws IOException {
        try (AuditLogBuilder auditLogBuilder = new AuditLogBuilder()) {
            PluginInfo pluginInfo = auditLogBuilder.getPluginInfo();
            Assertions.assertEquals(DigitalVersion.fromString("0.12.0"), pluginInfo.getVersion());
            Assertions.assertEquals(DigitalVersion.fromString("1.8.31"), pluginInfo.getJavaVersion());
            long start = System.currentTimeMillis();
            for (int i = 0; i < 10000; i++) {
                AuditEvent event = new AuditEvent.AuditEventBuilder().setEventType(EventType.AFTER_QUERY)
                        .setTimestamp(System.currentTimeMillis())
                        .setClientIp("127.0.0.1")
                        .setUser("user1")
                        .setAuthorizedUser("user2")
                        .setDb("db1")
                        .setState("EOF")
                        .setQueryTime(2000)
                        .setScanBytes(100000)
                        .setScanRows(200000)
                        .setReturnRows(i)
                        .setStmtId(1234)
                        .setStmt("select * from tbl1").build();
                if (auditLogBuilder.eventFilter(event.type)) {
                    auditLogBuilder.exec(event);
                }
            }
            long total = System.currentTimeMillis() - start;
            System.out.println("total(ms): " + total + ", avg: " + total / 10000.0);
        }
    }

    @Test
    public void testAuditEventProcessor() throws IOException {
        AuditEventProcessor processor = GlobalStateMgr.getCurrentState().getAuditEventProcessor();
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10000; i++) {
            AuditEvent event = new AuditEvent.AuditEventBuilder().setEventType(EventType.AFTER_QUERY)
                    .setTimestamp(System.currentTimeMillis())
                    .setClientIp("127.0.0.1")
                    .setUser("user1")
                    .setAuthorizedUser("user2")
                    .setDb("db1")
                    .setState("EOF")
                    .setQueryTime(2000)
                    .setScanBytes(100000)
                    .setScanRows(200000)
                    .setReturnRows(i)
                    .setStmtId(1234)
                    .setStmt("select * from tbl1").build();
            processor.handleAuditEvent(event);
        }
        long total = System.currentTimeMillis() - start;
        System.out.println("total(ms): " + total + ", avg: " + total / 10000.0);
    }
}
