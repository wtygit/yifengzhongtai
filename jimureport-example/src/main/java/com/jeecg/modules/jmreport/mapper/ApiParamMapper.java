package com.jeecg.modules.jmreport.mapper;

import com.jeecg.modules.jmreport.entity.ApiParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * API的查询参数Mapper
 */
@Mapper
public interface ApiParamMapper {
    @Select("SELECT * FROM api_param WHERE api_id = #{apiId}")
    List<ApiParam> selectByApiId(String apiId);
    
    int insert(ApiParam apiParam);
    
    int updateById(ApiParam apiParam);
    
    int deleteByApiId(String apiId);
}
