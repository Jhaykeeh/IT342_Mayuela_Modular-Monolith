package edu.cit.mayuela.supplier;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierOrderRepository extends JpaRepository<SupplierOrder, Long> {

    Optional<SupplierOrder> findByBuyerRef(String buyerRef);

    Optional<SupplierOrder> findFirstByProductIdAndStatusInOrderByCreatedAtAsc(String productId,
                                                                               List<ReorderStatus> statuses);

    List<SupplierOrder> findByStatusOrderByUpdatedAtAsc(ReorderStatus status);

    List<SupplierOrder> findByPoNumberNotNullAndStatusInOrderByUpdatedAtAsc(List<ReorderStatus> statuses);

    List<SupplierOrder> findAllByOrderByCreatedAtAsc();
}