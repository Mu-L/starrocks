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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/transaction/DatabaseTransactionMgrTest.java

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

package com.starrocks.transaction;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.FakeEditLog;
import com.starrocks.catalog.FakeGlobalStateMgr;
import com.starrocks.catalog.GlobalStateMgrTestUtil;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Table;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.ExceptionChecker;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.TimeUtils;
import com.starrocks.load.routineload.RLTaskTxnCommitAttachment;
import com.starrocks.server.GlobalStateMgr;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DatabaseTransactionMgrTest {

    private static FakeEditLog fakeEditLog;
    private static FakeGlobalStateMgr fakeGlobalStateMgr;
    private static FakeTransactionIDGenerator fakeTransactionIDGenerator;
    private static GlobalTransactionMgr masterTransMgr;
    private static GlobalTransactionMgr slaveTransMgr;
    private static GlobalStateMgr masterGlobalStateMgr;
    private static GlobalStateMgr slaveGlobalStateMgr;
    private static Map<String, Long> lableToTxnId;

    private static TransactionGraph transactionGraph = new TransactionGraph();

    private TransactionState.TxnCoordinator transactionSource =
            new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.FE, "localfe");

    @BeforeEach
    public void setUp() throws InstantiationException, IllegalAccessException, IllegalArgumentException,
            InvocationTargetException, NoSuchMethodException, SecurityException, StarRocksException {
        Config.label_keep_max_second = 10;
        fakeEditLog = new FakeEditLog();
        fakeGlobalStateMgr = new FakeGlobalStateMgr();
        fakeTransactionIDGenerator = new FakeTransactionIDGenerator();
        masterGlobalStateMgr = GlobalStateMgrTestUtil.createTestState();
        slaveGlobalStateMgr = GlobalStateMgrTestUtil.createTestState();

        masterTransMgr = masterGlobalStateMgr.getGlobalTransactionMgr();

        slaveTransMgr = slaveGlobalStateMgr.getGlobalTransactionMgr();

        lableToTxnId = addTransactionToTransactionMgr();
    }

    public void prepareCommittedTransaction() throws StarRocksException {
        long transactionId1 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable10,
                        transactionSource,
                        TransactionState.LoadJobSourceType.FRONTEND, Config.stream_load_default_timeout_second);

        // commit a transaction
        TabletCommitInfo tabletCommitInfo1 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId1);
        TabletCommitInfo tabletCommitInfo2 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId2);
        TabletCommitInfo tabletCommitInfo3 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId3);
        List<TabletCommitInfo> transTablets = Lists.newArrayList();
        transTablets.add(tabletCommitInfo1);
        transTablets.add(tabletCommitInfo2);
        transTablets.add(tabletCommitInfo3);
        masterTransMgr.commitTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId1, transTablets,
                Lists.newArrayList(), null);
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        assertEquals(TransactionStatus.COMMITTED, masterDbTransMgr.getTxnState(transactionId1).getStatus());
        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable10, transactionId1);

    }

    public Map<String, Long> addTransactionToTransactionMgr() throws StarRocksException {
        TransactionIdGenerator idGenerator = masterTransMgr.getTransactionIDGenerator();
        Assertions.assertEquals(idGenerator.peekNextTransactionId(), masterTransMgr.getMinActiveTxnId());
        Assertions.assertEquals(idGenerator.peekNextTransactionId(), masterTransMgr.getMinActiveCompactionTxnId());

        Map<String, Long> lableToTxnId = Maps.newHashMap();
        FakeGlobalStateMgr.setGlobalStateMgr(masterGlobalStateMgr);
        long transactionId1 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable1,
                        transactionSource,
                        TransactionState.LoadJobSourceType.FRONTEND, Config.stream_load_default_timeout_second);

        Assertions.assertEquals(transactionId1, masterTransMgr.getMinActiveTxnId());
        Assertions.assertEquals(idGenerator.peekNextTransactionId(), masterTransMgr.getMinActiveCompactionTxnId());

        // commit a transaction
        List<TabletCommitInfo> transTablets = buildTabletCommitInfoList();
        masterTransMgr.commitTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId1, transTablets,
                Lists.newArrayList(), null);
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        assertEquals(TransactionStatus.COMMITTED, masterDbTransMgr.getTxnState(transactionId1).getStatus());

        Assertions.assertEquals(transactionId1, masterTransMgr.getMinActiveTxnId());
        Assertions.assertEquals(idGenerator.peekNextTransactionId(), masterTransMgr.getMinActiveCompactionTxnId());

        masterTransMgr.finishTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId1, null);
        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable1, transactionId1);

        Assertions.assertEquals(idGenerator.peekNextTransactionId(), masterTransMgr.getMinActiveTxnId());
        Assertions.assertEquals(idGenerator.peekNextTransactionId(), masterTransMgr.getMinActiveCompactionTxnId());

        TransactionState.TxnCoordinator beTransactionSource =
                new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.BE, "be1");
        TransactionState.TxnCoordinator feTransactionSource =
                new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.FE, "fe1");
        long transactionId2 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable2,
                        beTransactionSource,
                        TransactionState.LoadJobSourceType.ROUTINE_LOAD_TASK,
                        Config.stream_load_default_timeout_second);
        long transactionId3 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable3,
                        beTransactionSource,
                        TransactionState.LoadJobSourceType.BACKEND_STREAMING,
                        Config.stream_load_default_timeout_second);
        long transactionId4 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable4,
                        beTransactionSource,
                        TransactionState.LoadJobSourceType.BACKEND_STREAMING,
                        Config.stream_load_default_timeout_second);
        long transactionId5 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable5,
                        feTransactionSource,
                        TransactionState.LoadJobSourceType.BACKEND_STREAMING,
                        Config.max_load_timeout_second);
        // for test batch
        long transactionId6 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1, Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable6,
                        beTransactionSource,
                        TransactionState.LoadJobSourceType.BACKEND_STREAMING,
                        Config.stream_load_default_timeout_second);
        long transactionId7 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1, Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable7,
                        beTransactionSource,
                        TransactionState.LoadJobSourceType.BACKEND_STREAMING,
                        Config.stream_load_default_timeout_second);
        long transactionId8 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1, Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable8,
                        beTransactionSource,
                        TransactionState.LoadJobSourceType.BACKEND_STREAMING,
                        Config.stream_load_default_timeout_second);


        masterTransMgr.commitTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId6, transTablets,
                Lists.newArrayList(), null);
        assertEquals(TransactionStatus.COMMITTED, masterDbTransMgr.getTxnState(transactionId6).getStatus());
        masterTransMgr.commitTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId7, transTablets,
                Lists.newArrayList(), null);
        assertEquals(TransactionStatus.COMMITTED, masterDbTransMgr.getTxnState(transactionId7).getStatus());
        masterTransMgr.commitTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId8, transTablets,
                Lists.newArrayList(), null);
        assertEquals(TransactionStatus.COMMITTED, masterDbTransMgr.getTxnState(transactionId8).getStatus());

        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable2, transactionId2);
        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable3, transactionId3);
        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable4, transactionId4);
        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable5, transactionId5);

        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable6, transactionId6);
        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable7, transactionId7);
        lableToTxnId.put(GlobalStateMgrTestUtil.testTxnLable8, transactionId8);

        Assertions.assertEquals(transactionId2, masterTransMgr.getMinActiveTxnId());

        transactionGraph.add(transactionId6, Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1));
        transactionGraph.add(transactionId7, Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1));
        transactionGraph.add(transactionId8, Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1));

        Deencapsulation.setField(masterDbTransMgr, "transactionGraph", transactionGraph);

        TransactionState transactionState1 = fakeEditLog.getTransaction(transactionId1);

        FakeGlobalStateMgr.setGlobalStateMgr(slaveGlobalStateMgr);
        slaveTransMgr.replayUpsertTransactionState(transactionState1);
        return lableToTxnId;
    }

    private List<TabletCommitInfo> buildTabletCommitInfoList() {
        TabletCommitInfo tabletCommitInfo1 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId1);
        TabletCommitInfo tabletCommitInfo2 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId2);
        TabletCommitInfo tabletCommitInfo3 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId3);
        List<TabletCommitInfo> transTablets = Lists.newArrayList();
        transTablets.add(tabletCommitInfo1);
        transTablets.add(tabletCommitInfo2);
        transTablets.add(tabletCommitInfo3);
        return transTablets;
    }

    @Test
    public void getLakeCompactionActiveTxnListTest() throws StarRocksException {
        TransactionState.TxnCoordinator feTransactionSource =
                new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.FE, "fe1");
        long committedCompactionTransactionId = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLableCompaction1,
                        feTransactionSource,
                        TransactionState.LoadJobSourceType.LAKE_COMPACTION,
                        Config.lake_compaction_default_timeout_second);

        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        List<TabletCommitInfo> transTablets = buildTabletCommitInfoList();
        masterTransMgr.commitTransaction(GlobalStateMgrTestUtil.testDbId1, committedCompactionTransactionId, transTablets,
                Lists.newArrayList(), null);
        assertEquals(TransactionStatus.COMMITTED, masterDbTransMgr.getTxnState(committedCompactionTransactionId).getStatus());

        long preparedCompactionTransactionId = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLableCompaction2,
                        feTransactionSource,
                        TransactionState.LoadJobSourceType.LAKE_COMPACTION,
                        Config.lake_compaction_default_timeout_second);

        Map<Long, Long> compactionActiveTxnMap = masterDbTransMgr.getLakeCompactionActiveTxnMap();
        Assertions.assertEquals(2, compactionActiveTxnMap.size());
        Assertions.assertTrue(compactionActiveTxnMap.containsKey(committedCompactionTransactionId));
        Assertions.assertTrue(compactionActiveTxnMap.containsKey(preparedCompactionTransactionId));

        // global transaction stats check
        Map<Long, Long> globalCompactionActiveTxnMap = masterTransMgr.getLakeCompactionActiveTxnStats();
        Assertions.assertEquals(2, globalCompactionActiveTxnMap.size());
        Assertions.assertTrue(globalCompactionActiveTxnMap.containsKey(committedCompactionTransactionId));
        Assertions.assertTrue(globalCompactionActiveTxnMap.containsKey(preparedCompactionTransactionId));
    }

    @Test
    public void testNormal() throws StarRocksException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        assertEquals(8, masterDbTransMgr.getTransactionNum());
        assertEquals(6, masterDbTransMgr.getRunningTxnNums());
        assertEquals(1, masterDbTransMgr.getRunningRoutineLoadTxnNums());
        assertEquals(1, masterDbTransMgr.getFinishedTxnNums());
        DatabaseTransactionMgr slaveDbTransMgr =
                slaveTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        assertEquals(1, slaveDbTransMgr.getTransactionNum());
        assertEquals(1, slaveDbTransMgr.getFinishedTxnNums());

        assertEquals(1, masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable1).size());
        assertEquals(1, masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable2).size());
        assertEquals(1, masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable3).size());
        assertEquals(1, masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable4).size());

        Long txnId1 =
                masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable1).iterator().next();
        assertEquals(txnId1, lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1));
        TransactionState transactionState1 =
                masterDbTransMgr.getTransactionState(lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1));
        assertEquals(txnId1.longValue(), transactionState1.getTransactionId());
        assertEquals(TransactionStatus.VISIBLE, transactionState1.getTransactionStatus());
        assertEquals(TransactionStatus.VISIBLE, masterDbTransMgr.getTxnState(txnId1.longValue()).getStatus());

        Long txnId2 =
                masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable2).iterator().next();
        assertEquals(txnId2, lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable2));
        TransactionState transactionState2 = masterDbTransMgr.getTransactionState(txnId2);
        assertEquals(txnId2.longValue(), transactionState2.getTransactionId());
        assertEquals(TransactionStatus.PREPARE, transactionState2.getTransactionStatus());
        assertEquals(TransactionStatus.PREPARE, masterDbTransMgr.getTxnState(txnId2.longValue()).getStatus());

        assertEquals(TransactionStatus.UNKNOWN, masterDbTransMgr.getTxnState(12134).getStatus());
    }

    @Test
    public void testAbortTransactionWithAttachment() throws StarRocksException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        long txnId1 = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1);
        Throwable exception = assertThrows(StarRocksException.class, () -> {
            TxnCommitAttachment txnCommitAttachment = new RLTaskTxnCommitAttachment();
            masterDbTransMgr.abortTransaction(txnId1, "test abort transaction", txnCommitAttachment);
        });
        assertThat(exception.getMessage(), containsString("transaction not found"));
    }

    @Test
    public void testAbortTransaction() throws StarRocksException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);

        long txnId2 = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable2);
        masterDbTransMgr.abortTransaction(txnId2, "test abort transaction", null);
        assertEquals(6, masterDbTransMgr.getRunningTxnNums());
        assertEquals(0, masterDbTransMgr.getRunningRoutineLoadTxnNums());
        assertEquals(2, masterDbTransMgr.getFinishedTxnNums());
        assertEquals(8, masterDbTransMgr.getTransactionNum());
        assertEquals(TransactionStatus.ABORTED, masterDbTransMgr.getTxnState(txnId2).getStatus());

        long txnId3 = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable3);
        masterDbTransMgr.abortTransaction(txnId3, "test abort transaction", null);
        assertEquals(5, masterDbTransMgr.getRunningTxnNums());
        assertEquals(0, masterDbTransMgr.getRunningRoutineLoadTxnNums());
        assertEquals(3, masterDbTransMgr.getFinishedTxnNums());
        assertEquals(8, masterDbTransMgr.getTransactionNum());
        assertEquals(TransactionStatus.ABORTED, masterDbTransMgr.getTxnState(txnId3).getStatus());
    }

    @Test
    public void testFinishTransactionTableRemove() throws StarRocksException {
        prepareCommittedTransaction();
        new MockUp<Database>() {
            @Mock
            public Table getTable(long tableId) {
                return null;
            }
        };

        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);

        long txnId = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable10);
        masterDbTransMgr.finishTransaction(txnId, null);
        assertEquals(TransactionStatus.VISIBLE, masterDbTransMgr.getTxnState(txnId).getStatus());
    }


    @Test
    public void testFinishTransactionPartitionRemove() throws StarRocksException {
        prepareCommittedTransaction();
        new MockUp<OlapTable>() {
            @Mock
            public PhysicalPartition getPhysicalPartition(long partitionId) {
                return null;
            }
        };

        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);

        long txnId = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable10);
        masterDbTransMgr.finishTransaction(txnId, null);
        assertEquals(TransactionStatus.VISIBLE, masterDbTransMgr.getTxnState(txnId).getStatus());
    }
    @Test
    public void testAbortTransactionWithNotFoundException() throws StarRocksException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);

        long txnId1 = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1);
        Throwable exception = assertThrows(StarRocksException.class,
                () -> masterDbTransMgr.abortTransaction(txnId1, "test abort transaction", null));
        assertThat(exception.getMessage(), containsString("transaction not found"));
    }

    @Test
    public void testGetTransactionIdByCoordinateBe() throws StarRocksException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        List<Pair<Long, Long>> transactionInfoList = masterDbTransMgr.getTransactionIdByCoordinateBe("be1", 10);
        assertEquals(6, transactionInfoList.size());
        assertEquals(GlobalStateMgrTestUtil.testDbId1, transactionInfoList.get(0).first.longValue());
        assertEquals(TransactionStatus.PREPARE,
                masterDbTransMgr.getTransactionState(transactionInfoList.get(0).second).getTransactionStatus());
    }

    @Test
    public void testGetSingleTranInfo() throws AnalysisException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        long txnId = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1);
        List<List<String>> singleTranInfos =
                masterDbTransMgr.getSingleTranInfo(GlobalStateMgrTestUtil.testDbId1, txnId);
        assertEquals(1, singleTranInfos.size());
        List<String> txnInfo = singleTranInfos.get(0);
        assertEquals("1000", txnInfo.get(0));
        assertEquals(GlobalStateMgrTestUtil.testTxnLable1, txnInfo.get(1));
        assertEquals("FE: localfe", txnInfo.get(2));
        assertEquals("VISIBLE", txnInfo.get(3));
        assertEquals("FRONTEND", txnInfo.get(4));
        long currentTime = System.currentTimeMillis();
        assertTrue(currentTime > TimeUtils.timeStringToLong(txnInfo.get(5)));
        assertTrue(currentTime > TimeUtils.timeStringToLong(txnInfo.get(6)));
        assertTrue(currentTime > TimeUtils.timeStringToLong(txnInfo.get(7)));
        assertTrue(currentTime > TimeUtils.timeStringToLong(txnInfo.get(8)));
        assertEquals("", txnInfo.get(9));
        assertEquals("0", txnInfo.get(10));
        assertEquals("[-1]", txnInfo.get(11));
        assertEquals(String.valueOf(Config.stream_load_default_timeout_second * 1000L), txnInfo.get(12));
    }

    @Test
    public void testRemoveExpiredTxns() throws AnalysisException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        Config.label_keep_max_second = -1;
        long currentMillis = System.currentTimeMillis();
        masterDbTransMgr.removeExpiredTxns(currentMillis);
        assertEquals(0, masterDbTransMgr.getFinishedTxnNums());
        assertEquals(7, masterDbTransMgr.getTransactionNum());
        assertNull(masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable1));
    }

    @Test
    public void testGetTableTransInfo() throws AnalysisException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        Long txnId = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1);
        List<List<Comparable>> tableTransInfos = masterDbTransMgr.getTableTransInfo(txnId);
        assertEquals(1, tableTransInfos.size());
        List<Comparable> tableTransInfo = tableTransInfos.get(0);
        assertEquals(2, tableTransInfo.size());
        assertEquals(2L, tableTransInfo.get(0));
        assertEquals("103", tableTransInfo.get(1));
    }

    @Test
    public void testGetPartitionTransInfo() throws AnalysisException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        Long txnId = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1);
        List<List<Comparable>> partitionTransInfos =
                masterDbTransMgr.getPartitionTransInfo(txnId, GlobalStateMgrTestUtil.testTableId1);
        assertEquals(1, partitionTransInfos.size());
        List<Comparable> partitionTransInfo = partitionTransInfos.get(0);
        assertEquals(2, partitionTransInfo.size());
        assertEquals(103L, partitionTransInfo.get(0));
        assertEquals(13L, partitionTransInfo.get(1));
    }

    @Test
    public void testDeleteTransaction() throws AnalysisException {
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        long txnId = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable1);
        TransactionState transactionState = masterDbTransMgr.getTransactionState(txnId);
        masterDbTransMgr.deleteTransaction(transactionState);
        assertEquals(6, masterDbTransMgr.getRunningTxnNums());
        assertEquals(1, masterDbTransMgr.getRunningRoutineLoadTxnNums());
        assertEquals(0, masterDbTransMgr.getFinishedTxnNums());
        assertEquals(7, masterDbTransMgr.getTransactionNum());
        assertNull(masterDbTransMgr.unprotectedGetTxnIdsByLabel(GlobalStateMgrTestUtil.testTxnLable1));
    }

    @Test
    public void testCheckRunningTxnExceedLimit() {
        int maxRunningTxnNumPerDb = Config.max_running_txn_num_per_db;
        DatabaseTransactionMgr mgr = new DatabaseTransactionMgr(0, masterGlobalStateMgr);
        Deencapsulation.setField(mgr, "runningTxnNums", maxRunningTxnNumPerDb);
        ExceptionChecker.expectThrowsNoException(
                () -> mgr.checkRunningTxnExceedLimit(TransactionState.LoadJobSourceType.ROUTINE_LOAD_TASK));
        ExceptionChecker.expectThrowsNoException(
                () -> mgr.checkRunningTxnExceedLimit(TransactionState.LoadJobSourceType.LAKE_COMPACTION));
        ExceptionChecker.expectThrows(RunningTxnExceedException.class,
                () -> mgr.checkRunningTxnExceedLimit(TransactionState.LoadJobSourceType.BACKEND_STREAMING));
    }

    @Test
    public void testGetReadyToPublishTxnListBatch() throws AnalysisException {
        DatabaseTransactionMgr masterDbTransMgr = masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        List<TransactionStateBatch> stateBatchesList = masterDbTransMgr.getReadyToPublishTxnListBatch();
        assertEquals(1, stateBatchesList.size());
        assertEquals(3, stateBatchesList.get(0).size());

        // Table was dropped before committing the txn 'testTxnLabel8'
        masterDbTransMgr.getLabelTransactionState(GlobalStateMgrTestUtil.testTxnLable8)
                .removeTable(GlobalStateMgrTestUtil.testTableId1);
        stateBatchesList = masterDbTransMgr.getReadyToPublishTxnListBatch();
        assertEquals(1, stateBatchesList.size());
        assertEquals(2, stateBatchesList.get(0).size());

        // Table was dropped before committing the txn 'testTxnLabel7'
        masterDbTransMgr.getLabelTransactionState(GlobalStateMgrTestUtil.testTxnLable7)
                .removeTable(GlobalStateMgrTestUtil.testTableId1);
        stateBatchesList = masterDbTransMgr.getReadyToPublishTxnListBatch();
        assertEquals(1, stateBatchesList.size());
        assertEquals(1, stateBatchesList.get(0).size());

        // Table was dropped before committing the txn 'testTxnLabel6'
        masterDbTransMgr.getLabelTransactionState(GlobalStateMgrTestUtil.testTxnLable6)
                .removeTable(GlobalStateMgrTestUtil.testTableId1);
        stateBatchesList = masterDbTransMgr.getReadyToPublishTxnListBatch();
        assertEquals(1, stateBatchesList.size());
        assertEquals(1, stateBatchesList.get(0).size());
    }

    @Test
    public void testFinishTransactionBatch() throws StarRocksException {
        FakeGlobalStateMgr.setGlobalStateMgr(masterGlobalStateMgr);
        DatabaseTransactionMgr masterDbTransMgr = masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);
        long txnId6 = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable6);
        TransactionState transactionState6 = masterDbTransMgr.getTransactionState(txnId6);
        long txnId7 = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable7);
        TransactionState transactionState7 = masterDbTransMgr.getTransactionState(txnId7);
        long txnId8 = lableToTxnId.get(GlobalStateMgrTestUtil.testTxnLable8);
        TransactionState transactionState8 = masterDbTransMgr.getTransactionState(txnId8);
        List<TransactionState> states = new ArrayList<>();
        states.add(transactionState6);
        states.add(transactionState7);
        states.add(transactionState8);

        new MockUp<Table>() {
            @Mock
            public boolean isCloudNativeTableOrMaterializedView() {
                return true;
            }
        };

        TransactionStateBatch stateBatch = new TransactionStateBatch(states);
        masterTransMgr.finishTransactionBatch(GlobalStateMgrTestUtil.testDbId1, stateBatch, null);

        assertEquals(3, masterDbTransMgr.getRunningTxnNums());
        assertEquals(4, masterDbTransMgr.getFinishedTxnNums());
        assertEquals(TransactionStatus.VISIBLE, transactionState6.getTransactionStatus());
        assertEquals(TransactionStatus.VISIBLE, transactionState7.getTransactionStatus());
        assertEquals(TransactionStatus.VISIBLE, transactionState8.getTransactionStatus());

        FakeGlobalStateMgr.setGlobalStateMgr(slaveGlobalStateMgr);
        slaveTransMgr.replayUpsertTransactionStateBatch(stateBatch);
        assertEquals(4, masterDbTransMgr.getFinishedTxnNums());
    }

    @Test
    public void testPublishVersionMissing() throws StarRocksException {
        TransactionIdGenerator idGenerator = masterTransMgr.getTransactionIDGenerator();
        DatabaseTransactionMgr masterDbTransMgr =
                masterTransMgr.getDatabaseTransactionMgr(GlobalStateMgrTestUtil.testDbId1);

        // begin transaction
        long transactionId1 = masterTransMgr
                .beginTransaction(GlobalStateMgrTestUtil.testDbId1,
                        Lists.newArrayList(GlobalStateMgrTestUtil.testTableId1),
                        GlobalStateMgrTestUtil.testTxnLable9,
                        transactionSource,
                        TransactionState.LoadJobSourceType.FRONTEND, Config.stream_load_default_timeout_second);

        // commit a transaction
        TabletCommitInfo tabletCommitInfo1 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId1);
        TabletCommitInfo tabletCommitInfo2 = new TabletCommitInfo(GlobalStateMgrTestUtil.testTabletId1,
                GlobalStateMgrTestUtil.testBackendId2);
        // skip replica 3
        List<TabletCommitInfo> transTablets = Lists.newArrayList();
        transTablets.add(tabletCommitInfo1);
        transTablets.add(tabletCommitInfo2);
        masterTransMgr.commitTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId1, transTablets,
                Lists.newArrayList(), null);
        masterTransMgr.finishTransaction(GlobalStateMgrTestUtil.testDbId1, transactionId1, null);
    }
}
