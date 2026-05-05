package com.jeecg.modules.jmreport.mapper;

import com.jeecg.modules.jmreport.entity.ApiInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

import org.apache.ibatis.annotations.Param;

/**
 * API基本信息Mapper
 */
@Mapper
public interface ApiInfoMapper {
    @Select("SELECT * FROM api_info")
    List<ApiInfo> selectList();
    
    @Select("SELECT * FROM api_info WHERE id = #{id}")
    ApiInfo selectById(String id);
    
    List<ApiInfo> selectListByCondition(
            @Param("apiName") String apiName,
            @Param("apiPath") String apiPath);
    
    int insert(ApiInfo apiInfo);
    
    int updateById(ApiInfo apiInfo);
    
    int deleteById(String id);
}
