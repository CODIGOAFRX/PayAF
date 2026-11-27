package dev.pedrogomez.payaf.adapter.out.persistencejpa;

import dev.pedrogomez.payaf.application.exception.ConcurrentPaymentModificationException;
import dev.pedrogomez.payaf.application.port.out.PaymentRepository;
import dev.pedrogomez.payaf.domain.payment.Payment;
import dev.pedrogomez.payaf.domain.payment.PaymentId;
import dev.pedrogomez.payaf.domain.payment.PaymentStatus;
import dev.pedrogomez.payaf.domain.shared.GatewayId;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * {@link PaymentRepository} con JPA sobre PostgreSQL.
 * <p>
 * Trabaja con {@link PaymentEntity} y traduce con {@link PaymentEntityMapper}: el agregado nunca
 * entra en el {@code EntityManager}.
 * <p>
 * <strong>Versión optimista.</strong> La columna {@code version} es el {@code @Version} de JPA, y
 * la del dominio es siempre esa más uno (0 = nunca guardado). Al guardar:
 * <ol>
 *   <li>Pago nuevo ({@code version() == 0}): si ya hay una fila con ese id, alguien lo insertó antes
 *       → {@link ConcurrentPaymentModificationException}. Si dos lo insertan a la vez, la clave
 *       primaria hace fallar al segundo y se traduce igual.</li>
 *   <li>Pago existente: si la fila no está o su versión no es la que se cargó, el pago ha cambiado
 *       entretanto → CME sin escribir nada. Si coincide, se copia y se hace {@code flush}: el
 *       {@code UPDATE ... WHERE version = ?} de Hibernate detecta a quien haya escrito entre la
 *       lectura y el {@code flush} ({@link OptimisticLockException}) → CME.</li>
 * </ol>
 * El {@code flush} se hace dentro de {@link #save} a propósito: así el conflicto salta aquí,
 * traducido, y no al confirmar la transacción con una excepción de Hibernate. Si el pago no ha
 * cambiado, Hibernate no escribiría nada y la versión no avanzaría, pero el contrato exige que
 * avance: en ese caso se fuerza con {@link LockModeType#PESSIMISTIC_FORCE_INCREMENT}.
 * <p>
 * Se llama siempre dentro de una transacción ({@link SpringTransactionRunner}), que es la que
 * abre y cierra el {@code EntityManager} compartido.
 */
public final class JpaPaymentRepository implements PaymentRepository {

    private final EntityManager em;

    /** @param em el {@code EntityManager} compartido (ligado a la transacción en curso) */
    public JpaPaymentRepository(EntityManager em) {
        this.em = Objects.requireNonNull(em);
    }

    @Override
    public void save(Payment payment) {
        Objects.requireNonNull(payment, "el pago no puede ser nulo");
        try {
            PaymentEntity entity = em.find(PaymentEntity.class, payment.id().value());
            if (payment.version() == 0) {
                if (entity != null) {
                    throw new ConcurrentPaymentModificationException(payment.id());
                }
                entity = PaymentEntityMapper.toNewEntity(payment);
                em.persist(entity);
                em.flush();
            } else {
                if (entity == null || entity.getVersion() + 1 != payment.version()) {
                    throw new ConcurrentPaymentModificationException(payment.id());
                }
                long before = entity.getVersion();
                PaymentEntityMapper.copyInto(payment, entity);
                em.flush();
                if (entity.getVersion() == before) {
                    em.lock(entity, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
                }
            }
            payment.markPersisted(entity.getVersion() + 1);
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException | EntityExistsException e) {
            throw new ConcurrentPaymentModificationException(payment.id());
        } catch (PersistenceException e) {
            if (e.getCause() instanceof ConstraintViolationException violation
                    && "payments_pkey".equals(violation.getConstraintName())) {
                throw new ConcurrentPaymentModificationException(payment.id());
            }
            throw e;
        }
    }

    @Override
    public Optional<Payment> findById(PaymentId id) {
        Objects.requireNonNull(id, "el identificador no puede ser nulo");
        return Optional.ofNullable(em.find(PaymentEntity.class, id.value())).map(PaymentEntityMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByGatewayReference(GatewayId gateway, String gatewayReference) {
        Objects.requireNonNull(gateway, "la pasarela no puede ser nula");
        Objects.requireNonNull(gatewayReference, "la referencia no puede ser nula");
        return em.createQuery("select p from PaymentEntity p where p.authorizedBy = :gateway "
                        + "and p.gatewayReference = :reference", PaymentEntity.class)
                .setParameter("gateway", gateway.value())
                .setParameter("reference", gatewayReference)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(PaymentEntityMapper::toDomain);
    }

    @Override
    public List<Payment> findRecent(int limit) {
        requirePositive(limit);
        return em.createQuery("select p from PaymentEntity p order by p.createdAt desc, p.id", PaymentEntity.class)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(PaymentEntityMapper::toDomain)
                .toList();
    }

    @Override
    public List<Payment> findPendingOlderThan(Instant cutoff, int limit) {
        Objects.requireNonNull(cutoff, "el corte no puede ser nulo");
        requirePositive(limit);
        return em.createQuery("select p from PaymentEntity p where p.status = :status and p.updatedAt < :cutoff "
                        + "order by p.updatedAt, p.id", PaymentEntity.class)
                .setParameter("status", PaymentStatus.PENDING.name())
                .setParameter("cutoff", cutoff)
                .setMaxResults(limit)
                .getResultList()
                .stream()
                .map(PaymentEntityMapper::toDomain)
                .toList();
    }

    private static void requirePositive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser al menos 1: " + limit);
        }
    }
}
