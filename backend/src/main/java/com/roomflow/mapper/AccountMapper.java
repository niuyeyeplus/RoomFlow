package com.roomflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.roomflow.domain.account.Account;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {}
