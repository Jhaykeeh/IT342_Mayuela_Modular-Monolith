package edu.cit.mayuela.shop;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findAllByOrderByCreatedAtDesc();
}
