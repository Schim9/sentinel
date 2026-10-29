package lu.kaminski.sentinel.evaluation;

import lu.kaminski.sentinel.domain.CheckState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CheckStateRepository extends JpaRepository<CheckState, String> {

    List<CheckState> findBySubjectKeyStartingWith(String prefix);
}
