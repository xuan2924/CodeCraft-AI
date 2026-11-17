package ai.codecraft.modules.workspace.repository;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TerminalRepository {
    @Update("UPDATE workspace_command SET terminal=true,terminal_ticket_hash=#{hash},terminal_ticket_expires_at=#{expires} WHERE id=#{id}::uuid AND NOT dispatched AND NOT terminal_ticket_consumed")
    int ticket(@Param("id") String id, @Param("hash") String hash, @Param("expires") Instant expires);

    @Select("SELECT id::text FROM workspace_command WHERE terminal_ticket_hash=#{hash} AND exercise_id=#{exercise}::uuid AND session_hash=#{session} AND user_id=#{user}::uuid AND terminal AND NOT pruned")
    String lookup(@Param("hash") String hash, @Param("exercise") String exercise, @Param("session") String session, @Param("user") String user);

    @Update("UPDATE workspace_command SET terminal_ticket_consumed=true,terminal_opened_at=#{now} WHERE id=#{id}::uuid AND terminal AND NOT terminal_ticket_consumed AND terminal_ticket_expires_at>#{now} AND NOT quiescent AND NOT stop_requested")
    int consume(@Param("id") String id, @Param("now") Instant now);

    @Update("UPDATE workspace_command SET terminal_closed_at=coalesce(terminal_closed_at,#{now}),terminal_ticket_hash=NULL WHERE id=#{id}::uuid AND terminal")
    void closed(@Param("id") String id, @Param("now") Instant now);

    @Select("SELECT id::text AS command_id,state,created_at,terminal_opened_at AS opened_at,terminal_closed_at AS closed_at,revision::text AS base_revision,snapshot->>'synchronizedRevision' AS synchronized_revision,synchronization,coalesce(snapshot->>'synchronizationReason','') AS reason,pruned FROM workspace_command WHERE exercise_id=#{exercise}::uuid AND terminal ORDER BY created_at DESC,id DESC LIMIT 100")
    List<ai.codecraft.modules.workspace.model.dto.TerminalAuditResponse> history(String exercise);

    @Select("SELECT id::text FROM workspace_command WHERE terminal AND NOT quiescent AND NOT terminal_ticket_consumed AND terminal_ticket_expires_at<=#{now} ORDER BY created_at LIMIT 32")
    List<String> abandoned(Instant now);
}
