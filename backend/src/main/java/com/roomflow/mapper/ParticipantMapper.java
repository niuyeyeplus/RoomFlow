package com.roomflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.roomflow.domain.participant.Participant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ParticipantMapper extends BaseMapper<Participant> {}
