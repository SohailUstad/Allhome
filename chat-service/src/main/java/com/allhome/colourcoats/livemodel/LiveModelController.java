package com.allhome.colourcoats.livemodel;

import java.security.Principal;
import java.time.ZoneId;

import com.allhome.colourcoats.prompt.PromptExceptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Console page "Agent model": the live model, changing it (warning, reason, confirmation) and its history. */
@Controller
@RequestMapping("/agent-model")
class LiveModelController {

	private final LiveModelService service;

	private final ZoneId zone;

	LiveModelController(LiveModelService service, @Value("${operator.time-zone:Asia/Kolkata}") ZoneId zone) {
		this.service = service;
		this.zone = zone;
	}

	@GetMapping
	String page(Model model) {
		model.addAttribute("live", service.current());
		model.addAttribute("allowed", service.allowed());
		model.addAttribute("history", service.history());
		model.addAttribute("defaultModel", service.defaultModel());
		model.addAttribute("zone", zone);
		return "agent-model";
	}

	@PostMapping
	String change(@RequestParam String model, @RequestParam(required = false) String reason,
			@RequestParam(defaultValue = "false") boolean confirmed, Principal principal, RedirectAttributes redirect) {
		try {
			service.change(model, reason, confirmed, principal.getName());
			redirect.addFlashAttribute("notice", model + " now answers visitors, from each conversation's next message.");
		}
		catch (PromptExceptions.RuleViolation ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
			redirect.addFlashAttribute("reason", reason);
		}
		return "redirect:/agent-model";
	}

}
