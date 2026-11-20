-- CodeRabbit generated schema. Event-scoped records preserve fork history.
CREATE TABLE rsi_promotion_ledger (
 event_id INTEGER PRIMARY KEY REFERENCES events(id) ON DELETE RESTRICT,
 rules_version INTEGER NOT NULL CHECK(rules_version >= 5),
 source_event_id INTEGER REFERENCES events(id) ON DELETE RESTRICT CHECK(source_event_id<event_id),
 payload TEXT NOT NULL CHECK(length(payload) BETWEEN 1 AND 65536),
 digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*')
);
CREATE TABLE subjective_beliefs (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 agent_id TEXT NOT NULL CHECK(length(agent_id) BETWEEN 1 AND 128),
 claim_id TEXT NOT NULL CHECK(length(claim_id) BETWEEN 1 AND 128),
 source_id TEXT NOT NULL CHECK(length(source_id) BETWEEN 1 AND 128),
 belief REAL NOT NULL CHECK(belief BETWEEN 0 AND 1),
 disbelief REAL NOT NULL CHECK(disbelief BETWEEN 0 AND 1),
 uncertainty REAL NOT NULL CHECK(uncertainty BETWEEN 0 AND 1),
 base_rate REAL NOT NULL CHECK(base_rate BETWEEN 0 AND 1),
 CHECK(abs(belief+disbelief+uncertainty-1)<0.000000001),
 PRIMARY KEY(event_id,agent_id,claim_id,source_id)
);
CREATE TABLE ecological_niche_structures (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 structure_id TEXT NOT NULL CHECK(length(structure_id) BETWEEN 1 AND 128),
 owner_id TEXT NOT NULL CHECK(length(owner_id) BETWEEN 1 AND 128),
 expires_tick INTEGER NOT NULL CHECK(expires_tick>=event_id),
 payload TEXT NOT NULL CHECK(length(payload) BETWEEN 1 AND 65536),
 PRIMARY KEY(event_id,structure_id)
);
CREATE TABLE tsetlin_automata_blobs (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 agent_id TEXT NOT NULL CHECK(length(agent_id) BETWEEN 1 AND 128),
 format_version INTEGER NOT NULL CHECK(format_version>0),
 automata BLOB NOT NULL CHECK(typeof(automata)='blob' AND length(automata) BETWEEN 1 AND 1048576),
 digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
 PRIMARY KEY(event_id,agent_id)
);
CREATE TABLE adico_institutions (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 institution_id TEXT NOT NULL CHECK(length(institution_id) BETWEEN 1 AND 128),
 attribute TEXT NOT NULL CHECK(length(attribute) BETWEEN 1 AND 128),
 deontic TEXT NOT NULL CHECK(deontic IN ('OBRIGADO','PERMITIDO','PROIBIDO')),
 aim TEXT NOT NULL CHECK(length(aim) BETWEEN 1 AND 256),
 condition TEXT NOT NULL CHECK(length(condition) BETWEEN 1 AND 4096),
 or_else TEXT NOT NULL CHECK(length(or_else) BETWEEN 1 AND 4096),
 PRIMARY KEY(event_id,institution_id)
);
CREATE TABLE double_entry_ledger (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 entry_id INTEGER NOT NULL CHECK(entry_id>=0),
 debit_account TEXT NOT NULL CHECK(length(debit_account) BETWEEN 1 AND 256),
 credit_account TEXT NOT NULL CHECK(length(credit_account) BETWEEN 1 AND 256 AND debit_account<>credit_account),
 resource TEXT NOT NULL CHECK(length(resource) BETWEEN 1 AND 64),
 amount INTEGER NOT NULL CHECK(typeof(amount)='integer' AND amount BETWEEN 1 AND 2147483647),
 PRIMARY KEY(event_id,entry_id)
);
CREATE TABLE bkt_fsrs_mastery (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 agent_id TEXT NOT NULL CHECK(length(agent_id) BETWEEN 1 AND 128),
 skill_id TEXT NOT NULL CHECK(length(skill_id) BETWEEN 1 AND 128),
 knowledge REAL NOT NULL CHECK(knowledge BETWEEN 0 AND 1),
 difficulty REAL NOT NULL CHECK(difficulty BETWEEN 1 AND 10),
 stability REAL NOT NULL CHECK(stability>0 AND stability<=1000000000),
 theta REAL NOT NULL CHECK(theta BETWEEN -4 AND 4),
 reviews INTEGER NOT NULL CHECK(reviews>=0),
 last_review_tick INTEGER NOT NULL CHECK(last_review_tick BETWEEN -1 AND event_id),
 CHECK((reviews=0 AND last_review_tick=-1) OR (reviews>0 AND last_review_tick>=0)),
 PRIMARY KEY(event_id,agent_id,skill_id)
);
CREATE TABLE voyager_skill_library (
 event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
 agent_id TEXT NOT NULL CHECK(length(agent_id) BETWEEN 1 AND 128),
 skill_id TEXT NOT NULL CHECK(length(skill_id) BETWEEN 1 AND 128),
 executions INTEGER NOT NULL CHECK(executions>=3),
 knowledge REAL NOT NULL CHECK(knowledge BETWEEN 0.75 AND 1),
 preconditions TEXT NOT NULL CHECK(length(preconditions) BETWEEN 1 AND 4096),
 program TEXT NOT NULL CHECK(length(program) BETWEEN 1 AND 65536),
 PRIMARY KEY(event_id,agent_id,skill_id)
);
CREATE INDEX rsi_source_event ON rsi_promotion_ledger(source_event_id);
CREATE INDEX beliefs_agent_claim ON subjective_beliefs(agent_id,claim_id,event_id);
CREATE INDEX structures_expiry ON ecological_niche_structures(expires_tick);
CREATE INDEX tsetlin_agent_event ON tsetlin_automata_blobs(agent_id,event_id);
CREATE INDEX adico_attribute ON adico_institutions(attribute,event_id);
CREATE INDEX ledger_debit ON double_entry_ledger(debit_account,resource,event_id);
CREATE INDEX ledger_credit ON double_entry_ledger(credit_account,resource,event_id);
CREATE INDEX mastery_agent_skill ON bkt_fsrs_mastery(agent_id,skill_id,event_id);
CREATE INDEX voyager_agent_skill ON voyager_skill_library(agent_id,skill_id,event_id);
