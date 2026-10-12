package com.financeos.module.importing.service;

import com.financeos.common.BusinessException;
import com.financeos.module.importing.entity.TransactionImportFileObject;
import com.financeos.module.importing.entity.TransactionImportSession;
import com.financeos.module.importing.mapper.TransactionImportFileObjectMapper;
import com.financeos.module.importing.mapper.TransactionImportSessionMapper;
import com.financeos.module.importing.storage.InvalidImportFileException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Metadata transactions only: Session first, then Registry references in lexical order. */
@Service
public class TransactionImportFileLifecycleService {
    private final TransactionImportFileObjectMapper files;
    private final TransactionImportSessionMapper sessions;
    private final TransactionTemplate transaction;

    public TransactionImportFileLifecycleService(TransactionImportFileObjectMapper files,
                                                 TransactionImportSessionMapper sessions,
                                                 PlatformTransactionManager manager) {
        this.files = files;
        this.sessions = sessions;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
    }

    private <T> T tx(Supplier<T> operation) {
        return transaction.execute(status -> operation.get());
    }

    public void bind(String storage, String deployment, String kind, String digest) {
        tx(() -> {
            files.bind(storage, deployment, kind, digest);
            if (!digest.equals(files.binding(storage, deployment, kind))) unavailable();
            return null;
        });
    }

    public TransactionImportFileObject reserve(String storage, String kind, Long user, UUID session) {
        TransactionImportFileObject object = new TransactionImportFileObject();
        object.setReference(UUID.randomUUID().toString());
        object.setStorageId(storage);
        object.setKind(kind);
        object.setOwnerUserId(user);
        object.setIntendedSessionId(session.toString());
        object.setLeaseOwner(UUID.randomUUID().toString());
        // A lost insert acknowledgement permits no I/O. Recovery discovers any committed reservation.
        tx(() -> { requireBinding(storage); files.reserve(object); return null; });
        return files.find(object.getReference());
    }

    public TransactionImportFileObject object(String reference) { return files.find(reference); }
    public void quarantine(String storage) { tx(() -> { files.quarantine(storage); return null; }); }

    public void authorizeWrite(TransactionImportFileObject expected) {
        tx(() -> {
            lockIntendedSession(expected);
            TransactionImportFileObject current = files.lock(expected.getReference());
            requireBinding(expected.getStorageId());
            if (current == null || !"RESERVED".equals(current.getState())
                    || current.getFenceEpoch() != expected.getFenceEpoch()
                    || !Objects.equals(current.getLeaseOwner(), expected.getLeaseOwner())
                    || !Boolean.TRUE.equals(files.leaseValid(current.getReference()))) unavailable();
            return null;
        });
    }

    public void completed(TransactionImportFileObject object) {
        tx(() -> {
            lockIntendedSession(object);
            requireBinding(object.getStorageId());
            if (files.complete(object.getReference(), object.getFenceEpoch(), object.getLeaseOwner()) != 1) unavailable();
            return null;
        });
    }

    public TransactionImportSession insertSession(TransactionImportSession draft) {
        return tx(() -> {
            TransactionImportFileObject raw = files.lock(draft.getTemporaryStorageReference());
            checkOwner(raw, draft.getUserId(), draft.getId());
            requireBinding(raw.getStorageId());
            if (!"RAW".equals(raw.getKind()) || !raw.isWriteComplete()) unavailable();
            if (sessions.insert(draft) != 1) throw new BusinessException(409, "Import session creation conflict");
            if (files.publish(raw.getReference(), draft.getId().toString()) != 1) unavailable();
            return draft;
        });
    }

    public record PublishedPlan(TransactionImportSession session, String retiredReference) { }

    public PublishedPlan publishPlan(TransactionImportSession snapshot, String reference,
                                               String mappingDigest, String rowsDigest, Instant now) {
        return tx(() -> {
            TransactionImportSession current = lockUsable(snapshot.getUserId(), snapshot.getId(), now);
            if (!Objects.equals(current.getRevision(), snapshot.getRevision())) conflict();
            lockReferences(reference, current.getPlanStorageReference());
            TransactionImportFileObject plan = files.find(reference);
            checkOwner(plan, current.getUserId(), current.getId());
            requireBinding(plan.getStorageId());
            if (!"PLAN".equals(plan.getKind()) || !plan.isWriteComplete()) unavailable();
            if (files.publish(reference, current.getId().toString()) != 1) unavailable();
            if (sessions.markPreviewReady(current.getId(), current.getUserId(), mappingDigest, rowsDigest,
                    reference, now, snapshot.getRevision()) != 1) conflict();
            if (current.getPlanStorageReference() != null) files.retire(current.getPlanStorageReference());
            return new PublishedPlan(sessions.findByIdAndUserId(current.getId(), current.getUserId()), current.getPlanStorageReference());
        });
    }

    public PublishedPlan invalidate(TransactionImportSession snapshot, Instant now) {
        return tx(() -> {
            TransactionImportSession current = lockUsable(snapshot.getUserId(), snapshot.getId(), now);
            if (!Objects.equals(current.getRevision(), snapshot.getRevision())) conflict();
            if ("MAPPING_REQUIRED".equals(current.getStatus()) && current.getPlanStorageReference() == null)
                return new PublishedPlan(current, null);
            lockReferences(current.getPlanStorageReference());
            if (sessions.invalidatePreview(current.getId(), current.getUserId(), now) != 1) conflict();
            if (current.getPlanStorageReference() != null) files.retire(current.getPlanStorageReference());
            return new PublishedPlan(sessions.findByIdAndUserId(current.getId(), current.getUserId()), current.getPlanStorageReference());
        });
    }

    public TransactionImportSession cancel(Long user, UUID id, Instant now) {
        return tx(() -> {
            TransactionImportSession current = lockUsable(user, id, now);
            lockReferences(current.getTemporaryStorageReference(), current.getPlanStorageReference());
            if (sessions.cancel(id, user, now) != 1) conflict();
            retireReferences(current);
            return sessions.findByIdAndUserId(id, user);
        });
    }

    public TransactionImportSession reconcileTerminal(Long user, UUID id, Instant now) {
        return tx(() -> {
            TransactionImportSession current = sessions.findByIdAndUserIdForUpdate(id, user);
            if (current == null) return null;
            if (active(current) && current.getExpiresAt().isAfter(now)) return null;
            lockReferences(current.getTemporaryStorageReference(), current.getPlanStorageReference());
            sessions.markExpired(id, user, now);
            retireReferences(current);
            return sessions.findByIdAndUserId(id, user);
        });
    }

    public void clearDeletedReferences(Long user, UUID id) {
        tx(() -> {
            TransactionImportSession current = sessions.findByIdAndUserIdForUpdate(id, user);
            if (current == null || active(current)) return null;
            lockReferences(current.getTemporaryStorageReference(), current.getPlanStorageReference());
            for (String ref : references(current)) {
                TransactionImportFileObject object = files.find(ref);
                if (object == null || !"DELETED".equals(object.getState())) return null;
            }
            sessions.clearCleanupReferences(id, user);
            return null;
        });
    }

    /** Called under the FS guard after the exact legacy object has been verified as a regular file. */
    public void adopt(String reference, String storage, String kind) {
        if (files.find(reference) != null) return;
        tx(() -> {
            List<TransactionImportSession> matches = sessions.findByStorageReference(reference);
            requireBinding(storage);
            if (matches.size() != 1) unavailable();
            TransactionImportSession snapshot = matches.getFirst();
            TransactionImportSession current = sessions.findByIdAndUserIdForUpdate(snapshot.getId(), snapshot.getUserId());
            if (current == null || !reference.equals("RAW".equals(kind)
                    ? current.getTemporaryStorageReference() : current.getPlanStorageReference())) unavailable();
            TransactionImportFileObject object = new TransactionImportFileObject();
            object.setReference(reference); object.setStorageId(storage); object.setKind(kind);
            object.setOwnerUserId(current.getUserId()); object.setIntendedSessionId(current.getId().toString());
            files.adopt(object);
            return null;
        });
    }

    public void authorizeRead(String reference, String storage, Long user, UUID intendedSession) {
        tx(() -> {
            TransactionImportFileObject snapshot = files.find(reference);
            if (snapshot == null) unavailable();
            TransactionImportSession session = lockIntendedSession(snapshot);
            TransactionImportFileObject object = files.lock(reference);
            requireBinding(storage);
            if (!storage.equals(object.getStorageId())) unavailable();
            checkOwner(object, user, intendedSession);
            if ("RESERVED".equals(object.getState())) {
                if (!object.isWriteComplete() || !Boolean.TRUE.equals(files.leaseValid(reference))) unavailable();
            } else if (!"LIVE".equals(object.getState()) || session == null || !active(session)
                    || !references(session).contains(reference)) unavailable();
            return null;
        });
    }

    /** Authoritative recovery after a possibly committed publication: never retire a current active reference. */
    public boolean retireUnpublished(String reference) {
        return tx(() -> {
            TransactionImportFileObject snapshot = files.find(reference);
            if (snapshot == null) return false;
            TransactionImportSession session = lockIntendedSession(snapshot);
            TransactionImportFileObject object = files.lock(reference);
            if (session != null && active(session) && references(session).contains(reference)) return false;
            if ("RESERVED".equals(object.getState())) files.retire(reference);
            return "RETIRED".equals(files.find(reference).getState());
        });
    }

    public TransactionImportFileObject prepareDeletion(String reference, String storage) {
        return tx(() -> {
            TransactionImportFileObject snapshot = files.find(reference);
            if (snapshot == null) unavailable();
            TransactionImportSession session = lockIntendedSession(snapshot);
            TransactionImportFileObject object = files.lock(reference);
            if (!storage.equals(object.getStorageId())) unavailable();
            requireBinding(storage);
            if (session != null && active(session) && references(session).contains(reference)) unavailable();
            if ("RESERVED".equals(object.getState())) {
                if (!Boolean.TRUE.equals(files.reservationReclaimable(reference))) unavailable();
                files.retire(reference);
            } else if ("LIVE".equals(object.getState())) {
                if (session != null && active(session)) unavailable();
                files.retire(reference);
            }
            return files.find(reference);
        });
    }

    public void deleted(String reference, long epoch) {
        tx(() -> {
            requireBinding(files.find(reference).getStorageId());
            if (files.deleted(reference, epoch) != 1 && !"DELETED".equals(files.find(reference).getState())) unavailable();
            return null;
        });
    }

    public void retry(String reference) { tx(() -> { files.retry(reference, "STORAGE_UNAVAILABLE"); return null; }); }
    public List<String> candidates(String storage) { return files.candidates(storage); }

    private TransactionImportSession lockIntendedSession(TransactionImportFileObject object) {
        if (object.getOwnerUserId() == null) return null;
        return sessions.findByIdAndUserIdForUpdate(UUID.fromString(object.getIntendedSessionId()), object.getOwnerUserId());
    }

    private TransactionImportSession lockUsable(Long user, UUID id, Instant now) {
        TransactionImportSession session = sessions.findByIdAndUserIdForUpdate(id, user);
        if (session == null) throw new BusinessException(404, "Import session not found");
        if (!active(session) || !session.getExpiresAt().isAfter(now)) conflict();
        return session;
    }

    private void checkOwner(TransactionImportFileObject object, Long user, UUID session) {
        if (object == null || !Objects.equals(object.getOwnerUserId(), user)
                || !session.toString().equals(object.getIntendedSessionId())) unavailable();
    }

    private void lockReferences(String... references) {
        Arrays.stream(references).filter(Objects::nonNull).distinct().sorted().forEach(files::lock);
    }

    private void retireReferences(TransactionImportSession session) { references(session).forEach(files::retire); }
    private List<String> references(TransactionImportSession session) {
        return Arrays.stream(new String[]{session.getTemporaryStorageReference(), session.getPlanStorageReference()})
                .filter(Objects::nonNull).distinct().sorted().toList();
    }
    private boolean active(TransactionImportSession session) {
        return "MAPPING_REQUIRED".equals(session.getStatus()) || "PREVIEW_READY".equals(session.getStatus());
    }
    private void requireBinding(String storage) { if (!files.bindingReady(storage)) unavailable(); }
    private void conflict() { throw new BusinessException(409, "Import session changed or is no longer usable"); }
    private void unavailable() { throw new InvalidImportFileException(409, "Managed import storage is unavailable"); }
}
