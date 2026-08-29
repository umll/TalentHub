package com.talenthub.mapper;

import com.talenthub.model.entity.ReconcileLog;
import com.talenthub.model.vo.ReconcileLogVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ReconcileLogMapper {

    int insert(ReconcileLog reconcileLog);

    List<ReconcileLogVO> selectRecent(@Param("limit") int limit);
}
