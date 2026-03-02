package com.myhockeystats.repository;

import com.myhockeystats.model.GamePerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface GamePerformanceRepository extends JpaRepository<GamePerformance, Long> {
    List<GamePerformance> findByGameId(Long gameId);
    List<GamePerformance> findByPlayerProfileId(Long playerProfileId);
}