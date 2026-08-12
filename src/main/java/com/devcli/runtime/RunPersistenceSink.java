package com.devcli.runtime;

/** Checkpoint、Patch Journal 与 Side-Git 向当前 Run 写引用的窄接口。 */
public interface RunPersistenceSink {
    RunPersistenceSink NO_OP = new RunPersistenceSink() {
        @Override
        public boolean saveRecoveryReferences(String checkpointRef, String patchJournalRef,
                                              String snapshotRef) {
            return false;
        }

        @Override
        public boolean clearRecoveryReferences(boolean checkpoint, boolean patchJournal,
                                               boolean snapshot) {
            return false;
        }
    };

    boolean saveRecoveryReferences(String checkpointRef, String patchJournalRef, String snapshotRef);

    boolean clearRecoveryReferences(boolean checkpoint, boolean patchJournal, boolean snapshot);
}
