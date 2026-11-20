-- Append-only experiment evidence, anchored to an existing immutable source event.
CREATE TABLE rsi_policy_trials (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 source_event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT CHECK(source_event_id<=event_id),
 trial_id TEXT NOT NULL CHECK(length(trial_id)=64 AND trial_id NOT GLOB '*[^0-9a-f]*'),
 stage TEXT NOT NULL CHECK(stage IN ('PROPOSED','TRAINED','REJECTED','VERIFIED','CANCELLED','ERROR')),
 proposal_stage TEXT NOT NULL DEFAULT 'PROPOSED' CHECK(proposal_stage='PROPOSED'),
 rules_version INTEGER NOT NULL CHECK(rules_version>=8),
 payload TEXT NOT NULL CHECK(length(payload) BETWEEN 1 AND 16384),
 digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
 PRIMARY KEY(trial_id,stage),
 FOREIGN KEY(trial_id,proposal_stage) REFERENCES rsi_policy_trials(trial_id,stage) DEFERRABLE INITIALLY DEFERRED
)
-- @statement
CREATE UNIQUE INDEX rsi_one_terminal ON rsi_policy_trials(trial_id) WHERE stage NOT IN ('PROPOSED','TRAINED')
-- @statement
CREATE INDEX rsi_trial_source ON rsi_policy_trials(source_event_id,event_id)
-- @statement
CREATE TRIGGER rsi_trials_no_update BEFORE UPDATE ON rsi_policy_trials BEGIN SELECT RAISE(ABORT,'RSI evidence is append-only'); END
-- @statement
CREATE TRIGGER rsi_trials_no_delete BEFORE DELETE ON rsi_policy_trials BEGIN SELECT RAISE(ABORT,'RSI evidence is append-only'); END
