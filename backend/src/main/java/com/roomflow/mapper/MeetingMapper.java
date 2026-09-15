package com.roomflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.roomflow.domain.meeting.Meeting;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MeetingMapper extends BaseMapper<Meeting> {}
