package com.dqh.dcs.web;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.dqh.dcs.config.AppProperties;

/**
 * web/ViewModelAdvice — chèn user/nav/basePath/assetVersion + Labels cho mọi
 * view (port src/views/render.ts registerViewLayer). Data route ghi đè được.
 *
 * assetVersion tính MỘT lần lúc nạp (mỗi lần restart → giá trị mới) để chống
 * cache app.css. Labels bean đã là @Component("labels") nên Thymeleaf gọi
 * @labels.docStatusLabel(...) trực tiếp; vẫn đưa vào model để tiện dùng.
 */
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class ViewModelAdvice {

    /** Phiên bản tài nguyên tĩnh (cache-busting). Tính một lần lúc nạp lớp. */
    private static final String ASSET_VERSION = Long.toString(System.currentTimeMillis(), 36);

    private final CurrentUserResolver currentUser;
    private final Labels labels;
    private final String basePath;

    public ViewModelAdvice(CurrentUserResolver currentUser, Labels labels, AppProperties props) {
        this.currentUser = currentUser;
        this.labels = labels;
        this.basePath = props.getBasePath();
    }

    @ModelAttribute
    public void addCommon(Model model) {
        if (!model.containsAttribute("user")) {
            model.addAttribute("user", currentUser.get());
        }
        if (!model.containsAttribute("nav")) {
            model.addAttribute("nav", currentUser.nav());
        }
        model.addAttribute("basePath", basePath);
        model.addAttribute("assetVersion", ASSET_VERSION);
        model.addAttribute("labels", labels);
    }
}
