package com.devcli.runtime;

import com.devcli.runtime.store.AttemptStatus;

/** Run 内部尝试的窄持久化出口。 */
public interface AttemptPersistence {
    AttemptPersistence NO_OP = new AttemptPersistence() {
        @Override
        public void started(AttemptData attempt) {
        }

        @Override
        public void finished(String attemptId, AttemptStatus status, String outcome) {
        }
    };

    void started(AttemptData attempt);

    void finished(String attemptId, AttemptStatus status, String outcome);

    record AttemptData(String id, String runId, String parentAttemptId,
                       AttemptKind kind, String scope, String reason,
                       int sequence, long backoffMillis) {
        public AttemptData {
            id = text(id);
            runId = text(runId);
            parentAttemptId = text(parentAttemptId);
            kind = kind == null ? AttemptKind.INITIAL : kind;
            scope = text(scope);
            reason = text(reason);
            sequence = Math.max(1, sequence);
            backoffMillis = Math.max(0, backoffMillis);
        }

        private static String text(String value) {
            return value == null ? "" : value;
        }
    }
}
