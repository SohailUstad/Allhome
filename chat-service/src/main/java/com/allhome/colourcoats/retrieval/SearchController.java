package com.allhome.colourcoats.retrieval;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator tool: shows exactly which knowledge the assistant would use for a question. */
@RestController
@RequestMapping("/api/search")
class SearchController {

	private final KnowledgeSearch search;

	SearchController(KnowledgeSearch search) {
		this.search = search;
	}

	@GetMapping
	List<RetrievedChunk> search(@RequestParam String q) {
		return search.search(q);
	}

}
