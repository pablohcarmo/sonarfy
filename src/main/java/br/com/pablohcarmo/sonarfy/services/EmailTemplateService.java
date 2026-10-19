package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.Locale;
import java.util.Map;

@Service
public class EmailTemplateService {

    private static final Locale PORTUGUESE_BRAZIL = Locale.forLanguageTag("pt-BR");
    private static final Map<String, String> ACTIVATION_ASSETS = Map.of(
            "statusIcon", "email-assets/mail.png"
    );
    private static final Map<String, String> RESEND_ASSETS = Map.of(
            "statusIcon", "email-assets/mail-check.png"
    );
    private static final Map<String, String> WELCOME_ASSETS = Map.of(
            "statusIcon", "email-assets/circle-check.png",
            "albumIcon", "email-assets/disc.png",
            "audioIcon", "email-assets/audio-lines.png",
            "communityIcon", "email-assets/users.png"
    );

    private final SpringTemplateEngine htmlTemplateEngine;
    private final SpringTemplateEngine textTemplateEngine;

    public EmailTemplateService(SpringTemplateEngine htmlTemplateEngine) {
        this.htmlTemplateEngine = htmlTemplateEngine;
        this.textTemplateEngine = createTextTemplateEngine();
    }

    public EmailContent activation(User user, String activationLink) {
        return render("activation", user, Map.of("activationLink", activationLink), ACTIVATION_ASSETS);
    }

    public EmailContent activationResend(User user, String activationLink) {
        return render("activation-resend", user, Map.of("activationLink", activationLink), RESEND_ASSETS);
    }

    public EmailContent welcome(User user, String accountUrl) {
        return render("welcome", user, Map.of("accountUrl", accountUrl), WELCOME_ASSETS);
    }

    private EmailContent render(String templateName,
                                User user,
                                Map<String, Object> variables,
                                Map<String, String> inlineResources) {
        Context context = new Context(PORTUGUESE_BRAZIL);
        context.setVariable("fullName", fullName(user));
        context.setVariable("year", Year.now().getValue());
        context.setVariables(variables);

        String html = htmlTemplateEngine.process("email/" + templateName, context);
        String text = textTemplateEngine.process(templateName, context);
        return new EmailContent(html, text, inlineResources);
    }

    private static String fullName(User user) {
        String name = user.getName() == null ? "" : user.getName().trim();
        String surname = user.getSurname() == null ? "" : user.getSurname().trim();
        return (name + " " + surname).trim();
    }

    private static SpringTemplateEngine createTextTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/text/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
