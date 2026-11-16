package ai.codecraft.modules.workspace.service;

import ai.codecraft.modules.workspace.repository.ExerciseReportRepository;
import org.springframework.stereotype.Service;

/** Report snapshots and usage are exercise-owned private data, including failed/unknown attempts. */
@Service
public class ReportExerciseData implements ExerciseDataContributor {
    private final ExerciseReportRepository reports;
    public ReportExerciseData(ExerciseReportRepository reports) { this.reports = reports; }
    @Override public long retainedBytes(String exercise) { return reports.bytes(exercise); }
    @Override public boolean quiescent(String exercise) { return !reports.active(exercise); }
    @Override public void erase(String exercise) { reports.eraseAttempts(exercise); reports.erase(exercise); }
}
