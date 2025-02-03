
CREATE KEYSPACE cycling IF NOT EXISTS WITH REPLICATION = {   'class' : 'SimpleStrategy','replication_factor' : 1};

CREATE TABLE cycling.test_table (test_id INT, insert_ts timestamp,   PRIMARY KEY (test_id));

insert into  cycling.test_table (test_id, insert_ts) value(1, now());