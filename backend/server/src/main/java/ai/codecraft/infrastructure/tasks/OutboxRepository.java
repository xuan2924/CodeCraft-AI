package ai.codecraft.infrastructure.tasks;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OutboxRepository {
    String COLUMNS = "id::text,kind,aggregate_id::text,exercise_id::text,generation,attempt,lane,state,lease_token::text,failures";
    @Select(value = """
        WITH picked AS (
            SELECT id FROM outbox_events WHERE lane=#{lane} AND available_at<=clock_timestamp()
              AND (state IN ('PENDING','SENT') OR (state='LEASED' AND lease_until<=clock_timestamp()) OR (state='DEAD' AND NOT dead_published))
            ORDER BY available_at,id LIMIT 4 FOR UPDATE SKIP LOCKED
        ) UPDATE outbox_events o SET state=CASE WHEN state='DEAD' THEN 'DEAD' ELSE 'LEASED' END,
            lease_token=#{token}::uuid,lease_until=clock_timestamp()+interval '30 seconds',
            available_at=clock_timestamp()+interval '30 seconds',publish_attempts=publish_attempts+1,updated_at=clock_timestamp()
          FROM picked WHERE o.id=picked.id
          RETURNING o.id::text,o.kind,o.aggregate_id::text,o.exercise_id::text,o.generation,o.attempt,o.lane,o.state,o.lease_token::text,o.failures
        """, affectData = true)
    List<OutboxEvent> claim(String lane, String token);
    @Select("SELECT " + COLUMNS + " FROM outbox_events WHERE id=#{id}::uuid FOR UPDATE")
    OutboxEvent lock(String id);
    @Update("""
        UPDATE outbox_events SET state=CASE WHEN state='DEAD' THEN 'DEAD' ELSE 'SENT' END,
          dead_published=(state='DEAD'),available_at=clock_timestamp()+interval '10 minutes',updated_at=clock_timestamp()
        WHERE id=#{id}::uuid AND lease_token=#{token}::uuid AND state IN ('LEASED','DEAD')
        """)
    void published(String id, String token);
    @Update("""
        UPDATE outbox_events SET state=CASE WHEN state='DEAD' THEN 'DEAD' ELSE 'PENDING' END,
          available_at=clock_timestamp()+make_interval(secs => least(300,power(2,least(publish_attempts,8)))::int),updated_at=clock_timestamp()
        WHERE id=#{id}::uuid AND lease_token=#{token}::uuid AND state IN ('LEASED','DEAD')
        """)
    void publishFailed(String id, String token);
    @Insert("""
        INSERT INTO outbox_consumption(event_id,state,token,lease_until) VALUES(#{id}::uuid,'PROCESSING',#{token}::uuid,clock_timestamp()+interval '5 minutes')
        ON CONFLICT(event_id) DO UPDATE SET state='PROCESSING',token=excluded.token,lease_until=excluded.lease_until,updated_at=clock_timestamp()
        WHERE outbox_consumption.state='PROCESSING' AND outbox_consumption.lease_until<=clock_timestamp()
        """)
    int begin(String id, String token);
    @Update("UPDATE outbox_consumption SET state=#{state},updated_at=clock_timestamp() WHERE event_id=#{id}::uuid AND token=#{token}::uuid AND state='PROCESSING'")
    int finish(String id, String token, String state);
    @Update("UPDATE outbox_consumption SET lease_until=clock_timestamp()+make_interval(secs => #{delay}),updated_at=clock_timestamp() WHERE event_id=#{id}::uuid AND token=#{token}::uuid AND state='PROCESSING'")
    int release(String id, String token, int delay);
    @Update("""
        UPDATE outbox_events SET state=#{state},failures=failures+#{increment},lease_token=NULL,
          available_at=clock_timestamp()+make_interval(secs => #{delay}),updated_at=clock_timestamp() WHERE id=#{id}::uuid
        """)
    void outcome(String id, String state, int increment, int delay);
    @Insert("INSERT INTO outbox_dead_letter(event_id,reason,failures) VALUES(#{id}::uuid,#{reason},#{failures}) ON CONFLICT(event_id) DO NOTHING")
    void dead(String id, String reason, int failures);
    @Update("""
        WITH expired AS (SELECT id FROM outbox_events WHERE state='DONE' AND updated_at<clock_timestamp()-interval '7 days' LIMIT 100),
        receipts AS (DELETE FROM outbox_consumption WHERE event_id IN (SELECT id FROM expired)),
        dead AS (DELETE FROM outbox_dead_letter WHERE event_id IN (SELECT id FROM expired))
        DELETE FROM outbox_events WHERE id IN (SELECT id FROM expired)
        """)
    void prune();
    @Select("""
        SELECT coalesce((SELECT sum(pg_column_size(o)) FROM outbox_events o WHERE exercise_id=#{exercise}::uuid),0)
          +coalesce((SELECT sum(pg_column_size(c)) FROM outbox_consumption c JOIN outbox_events o ON o.id=c.event_id WHERE o.exercise_id=#{exercise}::uuid),0)
          +coalesce((SELECT sum(pg_column_size(d)) FROM outbox_dead_letter d JOIN outbox_events o ON o.id=d.event_id WHERE o.exercise_id=#{exercise}::uuid),0)
        """)
    long bytes(String exercise);
    @Select("SELECT EXISTS(SELECT 1 FROM outbox_consumption c JOIN outbox_events o ON o.id=c.event_id WHERE o.exercise_id=#{exercise}::uuid AND c.state='PROCESSING' AND c.lease_until>clock_timestamp())")
    boolean active(String exercise);
    @Update("UPDATE outbox_events SET state='DONE',lease_token=NULL,updated_at=clock_timestamp() WHERE exercise_id=#{exercise}::uuid")
    void retire(String exercise);
}
