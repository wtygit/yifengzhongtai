package com.jeecg.modules.jmreport.mapper;

import com.jeecg.modules.jmreport.entity.ApiField;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * API返回的字段信息Mapper
 */
@Mapper
public interface ApiFieldMapper {
    @Select("SELECT id, api_id, database_name, table_name, field_name, alias, data_type, is_primary, is_required, description FROM api_field WHERE api_id = #{apiId}")
    List<ApiField> selectByApiId(String apiId);
    
    int insert(ApiField apiField);
    
    int updateById(ApiField apiField);
    
    int deleteByApiId(String apiId);
}
