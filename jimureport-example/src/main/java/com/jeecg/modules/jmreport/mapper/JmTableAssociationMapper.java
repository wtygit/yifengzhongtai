package com.jeecg.modules.jmreport.mapper;

import com.jeecg.modules.jmreport.entity.JmTableAssociation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JmTableAssociationMapper {
    int insert(JmTableAssociation entity);
    JmTableAssociation selectById(String id);
}

