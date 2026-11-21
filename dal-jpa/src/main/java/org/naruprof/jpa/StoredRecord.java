package org.naruprof.jpa;

import jakarta.persistence.*;

@Entity
@Table(name="learning_records", indexes=@Index(columnList="collectionName"))
class StoredRecord {
    @Id @Column(length=1024) String recordKey;
    @Column(nullable=false) String collectionName;
    @Lob @Column(nullable=false) String payload;
    protected StoredRecord() {}
    StoredRecord(String key, String collection, String payload) {
        this.recordKey = key;
        this.collectionName = collection;
        this.payload = payload;
    }
}
