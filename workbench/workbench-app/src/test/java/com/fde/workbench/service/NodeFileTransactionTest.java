package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeFileTransactionTest {

    @TempDir
    Path directory;

    private final NodeStoragePaths storagePaths = new NodeStoragePaths(null);

    @BeforeEach
    void beginSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void rollbackRemovesPublishedFileAndAllowsRetry() throws Exception {
        Path staged = Files.writeString(directory.resolve(".staged-input"), "新内容");
        Path target = directory.resolve("item.v2.md");
        storagePaths.publishStagedFile(staged, target);
        assertEquals("新内容", Files.readString(target));

        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertFalse(Files.exists(target));
        assertTrue(Files.exists(staged));

        storagePaths.publishStagedFile(staged, target);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        storagePaths.deleteQuietly(staged);
        assertEquals("新内容", Files.readString(target));
    }

    @Test
    void rollbackAfterPublishConflictDoesNotRemoveExistingVersion() throws Exception {
        Path staged = Files.writeString(directory.resolve(".staged-output"), "新内容");
        Path target = Files.writeString(directory.resolve("item.v2.md"), "旧内容");

        assertThrows(BizException.class, () -> storagePaths.publishStagedFile(staged, target));
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals("旧内容", Files.readString(target));
    }

    private static void complete(int status) {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
        TransactionSynchronizationManager.initSynchronization();
    }
}
