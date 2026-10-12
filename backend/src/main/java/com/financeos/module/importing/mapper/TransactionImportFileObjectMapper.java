package com.financeos.module.importing.mapper;

import com.financeos.module.importing.entity.TransactionImportFileObject;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface TransactionImportFileObjectMapper {
    String COLUMNS = "reference, storage_id::text, kind, owner_user_id, intended_session_id::text, session_id::text, state, write_complete, fence_epoch, lease_owner::text, lease_until, created_at";

    @Insert("""
            INSERT INTO transaction_import_storage_bindings(storage_id,deployment_id,kind,binding_digest)
            VALUES (CAST(#{storage} AS UUID),CAST(#{deployment} AS UUID),#{kind},#{digest}) ON CONFLICT(storage_id) DO NOTHING
            """)
    int bind(@Param("storage") String storage, @Param("deployment") String deployment, @Param("kind") String kind, @Param("digest") String digest);

    @Select("SELECT binding_digest FROM transaction_import_storage_bindings WHERE storage_id=CAST(#{storage} AS UUID) AND deployment_id=CAST(#{deployment} AS UUID) AND kind=#{kind} AND NOT quarantined")
    String binding(@Param("storage") String storage, @Param("deployment") String deployment, @Param("kind") String kind);

    @Update("UPDATE transaction_import_storage_bindings SET quarantined=TRUE WHERE storage_id=CAST(#{storage} AS UUID)")
    int quarantine(String storage);

    @Select("SELECT EXISTS(SELECT 1 FROM transaction_import_storage_bindings WHERE storage_id=CAST(#{storage} AS UUID) AND NOT quarantined)")
    boolean bindingReady(String storage);

    @Insert("""
            INSERT INTO transaction_import_file_objects(reference,storage_id,kind,owner_user_id,intended_session_id,lease_owner,lease_until)
            VALUES (#{reference},CAST(#{storageId} AS UUID),#{kind},#{ownerUserId},CAST(#{intendedSessionId} AS UUID),CAST(#{leaseOwner} AS UUID),CURRENT_TIMESTAMP+INTERVAL '1 minute')
            """)
    int reserve(TransactionImportFileObject object);

    @Select("SELECT " + COLUMNS + " FROM transaction_import_file_objects WHERE reference=#{reference}")
    TransactionImportFileObject find(String reference);

    @Select("SELECT " + COLUMNS + " FROM transaction_import_file_objects WHERE reference=#{reference} FOR UPDATE")
    TransactionImportFileObject lock(String reference);

    @Select("SELECT lease_until > CURRENT_TIMESTAMP FROM transaction_import_file_objects WHERE reference=#{reference}")
    Boolean leaseValid(String reference);

    @Update("""
            UPDATE transaction_import_file_objects SET write_complete=TRUE,completed_at=CURRENT_TIMESTAMP
            WHERE reference=#{reference} AND state='RESERVED' AND fence_epoch=#{epoch}
                AND lease_owner=CAST(#{owner} AS UUID) AND lease_until>CURRENT_TIMESTAMP
            """)
    int complete(@Param("reference") String reference, @Param("epoch") long epoch, @Param("owner") String owner);

    @Update("""
            UPDATE transaction_import_file_objects SET state='LIVE',session_id=CAST(#{session} AS UUID)
            WHERE reference=#{reference} AND state='RESERVED' AND write_complete
                AND lease_until>CURRENT_TIMESTAMP
            """)
    int publish(@Param("reference") String reference, @Param("session") String session);

    @Update("""
            UPDATE transaction_import_file_objects SET state='RETIRED',fence_epoch=fence_epoch+1,
                retired_at=CURRENT_TIMESTAMP,next_attempt_at=CURRENT_TIMESTAMP
            WHERE reference=#{reference} AND state IN ('RESERVED','LIVE')
            """)
    int retire(String reference);

    @Update("""
            UPDATE transaction_import_file_objects SET state='DELETED',deleted_at=CURRENT_TIMESTAMP,last_failure_code=NULL
            WHERE reference=#{reference} AND state='RETIRED' AND fence_epoch=#{epoch}
            """)
    int deleted(@Param("reference") String reference, @Param("epoch") long epoch);

    @Update("""
            UPDATE transaction_import_file_objects SET attempt_count=attempt_count+1,last_failure_code=#{code},
                next_attempt_at=CURRENT_TIMESTAMP+INTERVAL '1 second'
            WHERE reference=#{reference} AND state='RETIRED'
            """)
    int retry(@Param("reference") String reference, @Param("code") String code);

    @Select("""
            SELECT reference FROM transaction_import_file_objects
            WHERE storage_id=CAST(#{storage} AS UUID) AND
                ((state='RETIRED' AND next_attempt_at<=CURRENT_TIMESTAMP) OR
                 (state='RESERVED' AND created_at<=CURRENT_TIMESTAMP-INTERVAL '24 hours' AND lease_until<=CURRENT_TIMESTAMP) OR
                 (state='LIVE' AND session_id IS NULL))
            ORDER BY reference LIMIT 100
            """)
    List<String> candidates(String storage);

    @Select("""
            SELECT created_at<=CURRENT_TIMESTAMP-INTERVAL '24 hours' AND lease_until<=CURRENT_TIMESTAMP
            FROM transaction_import_file_objects WHERE reference=#{reference}
            """)
    Boolean reservationReclaimable(String reference);

    @Insert("""
            INSERT INTO transaction_import_file_objects(reference,storage_id,kind,owner_user_id,intended_session_id,session_id,state,write_complete,completed_at)
            VALUES(#{reference},CAST(#{storageId} AS UUID),#{kind},#{ownerUserId},CAST(#{intendedSessionId} AS UUID),CAST(#{intendedSessionId} AS UUID),'LIVE',TRUE,CURRENT_TIMESTAMP)
            ON CONFLICT(reference) DO NOTHING
            """)
    int adopt(TransactionImportFileObject object);
}
