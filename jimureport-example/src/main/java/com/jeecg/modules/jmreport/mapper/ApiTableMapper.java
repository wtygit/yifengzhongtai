package com.jeecg.modules.jmreport.mapper;

import com.jeecg.modules.jmreport.entity.ApiTable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * API关联的数据库表Mapper
 */
@Mapper
public interface ApiTableMapper {
    @Select("SELECT id, api_id, database_name, table_name, alias, join_type, join_condition FROM api_table WHERE api_id = #{apiId}")
    List<ApiTable> selectByApiId(String apiId);
    
    int insert(ApiTable apiTable);
    
    int updateById(ApiTable apiTable);
    
    int deleteByApiId(String apiId);
}
