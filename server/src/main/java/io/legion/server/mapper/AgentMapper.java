package io.legion.server.mapper;

import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.legion.server.domain.AgentRow;

@Mapper
public interface AgentMapper {

    AgentRow findById(@Param("id") UUID id);
}