CREATE TABLE lora_adapter_checkpoints (
    event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    agent_id TEXT NOT NULL CHECK(length(agent_id) BETWEEN 1 AND 128),
    format_version INTEGER NOT NULL CHECK(format_version=1),
    steps INTEGER NOT NULL CHECK(typeof(steps)='integer' AND steps BETWEEN 0 AND 1000000000000),
    source_event_id INTEGER REFERENCES events(id) ON DELETE RESTRICT,
    source_hash TEXT NOT NULL,
    base_hash TEXT NOT NULL,
    adapter BLOB NOT NULL CHECK(typeof(adapter)='blob' AND length(adapter)=2592),
    digest TEXT NOT NULL CHECK(length(digest)=64 AND digest NOT GLOB '*[^0-9a-f]*'),
    PRIMARY KEY(event_id,agent_id),
    CHECK((steps=0 AND source_event_id IS NULL AND source_hash='' AND base_hash='') OR
          (steps>0 AND source_event_id>0 AND source_event_id<event_id AND length(source_hash)=64 AND source_hash NOT GLOB '*[^0-9a-f]*' AND length(base_hash)=64 AND base_hash NOT GLOB '*[^0-9a-f]*'))
);
CREATE INDEX lora_agent_history ON lora_adapter_checkpoints(agent_id,event_id);
