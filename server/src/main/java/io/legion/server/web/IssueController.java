package io.legion.server.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.legion.contracts.CreateIssueRequest;
import io.legion.contracts.IssueDetailDto;
import io.legion.contracts.IssueDto;
import io.legion.server.service.IssueService;

@RestController
@RequestMapping("/api/issues")
public class IssueController {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @GetMapping
    public List<IssueDto> list() {
        return issueService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssueDto create(@RequestBody CreateIssueRequest req) {
        return issueService.create(req);
    }

    @GetMapping("/{id}")
    public IssueDetailDto get(@PathVariable UUID id) {
        return issueService.get(id);
    }
}