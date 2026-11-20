#!/usr/bin/env python3
"""Round-trip an actual trained C++ optimizer through the shipped SQLite7 schema."""
import hashlib
import pathlib
import sqlite3
import subprocess
import sys
import tempfile
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as directory:
    path=pathlib.Path(directory)/'adapter.bin'
    subprocess.run([sys.argv[1],'export',str(path)],check=True)
    original=path.read_bytes()
    assert len(original)==2592 and int.from_bytes(original[16:24],'little')==296
    database=pathlib.Path(directory)/'optimizer.db'
    with sqlite3.connect(database) as db:
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('CREATE TABLE events(id INTEGER PRIMARY KEY)')
        db.executemany('INSERT INTO events VALUES(?)',[(296,),(297,),(300,)])
        db.executescript((root/'app/src/main/assets/database/gea-v7.sql').read_text())
        db.execute('INSERT INTO lora_adapter_checkpoints VALUES(?,?,?,?,?,?,?,?,?)',(297,'agent',1,296,296,'a'*64,'b'*64,original,hashlib.sha256(original).hexdigest()))
    # New connection/process: no live native instance, Java cache or SQLite connection remains.
    with sqlite3.connect(database) as db:
        blob,digest=db.execute('SELECT adapter,digest FROM lora_adapter_checkpoints WHERE agent_id=? ORDER BY event_id DESC LIMIT 1',('agent',)).fetchone()
        assert hashlib.sha256(blob).hexdigest()==digest and blob==original
        path.write_bytes(blob)
        subprocess.run([sys.argv[1],'resume',str(path)],check=True)
        db.execute('ATTACH DATABASE ? AS fork',(str(pathlib.Path(directory)/'fork.db'),))
        db.execute('CREATE TABLE fork.events(id INTEGER PRIMARY KEY)')
        db.executemany('INSERT INTO fork.events VALUES(?)',[(296,),(297,)])
        for statement in (root/'app/src/main/assets/database/gea-v7.sql').read_text().split(';'):
            if statement.strip():
                statement=statement.replace('CREATE TABLE lora_adapter_checkpoints','CREATE TABLE fork.lora_adapter_checkpoints').replace('CREATE INDEX lora_agent_history ON','CREATE INDEX fork.lora_agent_history ON')
                db.execute(statement)
        db.execute('INSERT INTO fork.lora_adapter_checkpoints SELECT * FROM main.lora_adapter_checkpoints WHERE event_id<=296')
        assert db.execute('SELECT count(*) FROM fork.lora_adapter_checkpoints').fetchone()[0]==0
        db.execute('INSERT INTO fork.lora_adapter_checkpoints SELECT * FROM main.lora_adapter_checkpoints WHERE event_id<=297')
        assert db.execute('SELECT adapter FROM fork.lora_adapter_checkpoints').fetchone()[0]==original
        damaged=bytearray(blob);damaged[0]^=1
        path.write_bytes(damaged)
        result=subprocess.run([sys.argv[1],'resume',str(path)],check=False)
        assert result.returncode==2, result.returncode
print('Actual C++ LoRA optimizer SQLite7 BLOB/reopen/prefix fork/40-step exact continuation/corrupt decode passed')
