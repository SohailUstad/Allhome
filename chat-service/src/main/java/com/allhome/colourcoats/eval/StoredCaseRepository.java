package com.allhome.colourcoats.eval;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface StoredCaseRepository extends JpaRepository<StoredCase, String> {

	List<StoredCase> findAllByOrderByIdAsc();

}
