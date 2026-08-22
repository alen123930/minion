package io.legion.server.mapper;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.legion.server.domain.CommentRow;

@Mapper
public interface CommentMapper {

    /** 某 issue 的全部评论，按创建时间升序（详情页时间线）。 */
    List<CommentRow> listByIssue(@Param("issueId") UUID issueId);

    int insert(CommentRow row);
}