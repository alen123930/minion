package io.legion.server.mapper;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.legion.server.domain.IssueRow;

@Mapper
public interface IssueMapper {

    List<IssueRow> listAll();

    IssueRow findById(@Param("id") UUID id);

    /** 插入一行，返回受影响行数；id/created_at/updated_at 由调用方显式赋值。 */
    int insert(IssueRow row);
}