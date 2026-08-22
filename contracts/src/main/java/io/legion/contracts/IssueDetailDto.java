package io.legion.contracts;

import java.util.List;

/** issue 详情：issue 行 + 其全部评论（设计 §4.2 GET /api/issues/{id}）。 */
public record IssueDetailDto(IssueDto issue, List<CommentDto> comments) {
}