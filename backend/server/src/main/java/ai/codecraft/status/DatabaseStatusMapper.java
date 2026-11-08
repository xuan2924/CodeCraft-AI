package ai.codecraft.status;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DatabaseStatusMapper {
    @Select("SELECT count(*) = 1 FROM information_schema.schemata WHERE schema_name = 'codecraft'")
    @Options(timeout = 2, useCache = false)
    boolean isMigrated();
}
