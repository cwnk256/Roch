CREATE TABLE desktop_index_connection (
    singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
    collection_name varchar(128) NOT NULL
);
