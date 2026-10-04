package com.allhome.colourcoats.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    private final String widgetCode;
    private final String opener;

    public PageController(@Value("${salesiq.widget-code:}") String widgetCode,
                          @Value("${salesiq.opener}") String opener) {
        this.widgetCode = widgetCode;
        this.opener = opener;
    }

    /** Public site: SalesIQ widget (bottom right) plus ColourCoats' own direct chat panel (bottom left). */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("salesiqWidgetCode", widgetCode);
        model.addAttribute("opener", opener);
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
