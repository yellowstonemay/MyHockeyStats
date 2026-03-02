package com.myhockeystats.repository;

import com.myhockeystats.model.Season;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SeasonRepository extends JpaRepository<Season, Long> {
    List<Season> findByPlayerProfileId(Long playerProfileId);
}