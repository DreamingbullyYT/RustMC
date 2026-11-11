fn format_downloads(n: u64) -> String {
    if n >= 1_000_000 {
        format!("{:.1}M", n as f64 / 1_000_000.0)
    } else if n >= 1_000 {
        format!("{:.1}K", n as f64 / 1_000.0)
    } else {
        n.to_string()
    }
}

fn capitalize(s: &str) -> String {
    let mut chars = s.chars();
    match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => String::new(),
    }
}

fn wrap_text_lines(ui: &Ui, face: Face, size: f32, text: &str, max_w: f32) -> Vec<String> {
    let mut lines = Vec::new();
    for raw_line in text.lines() {
        let trimmed = raw_line.trim();
        if trimmed.is_empty() {
            if lines.last().is_some_and(|l: &String| !l.is_empty()) {
                lines.push(String::new());
            }
            continue;
        }
        let mut current = String::new();
        for word in trimmed.split_whitespace() {
            let candidate = if current.is_empty() {
                word.to_owned()
            } else {
                format!("{current} {word}")
            };
            if ui.measure(face, size, &candidate) <= max_w || current.is_empty() {
                current = candidate;
            } else {
                lines.push(current);
                current = word.to_owned();
            }
            if lines.len() >= 16 {
                break;
            }
        }
        if !current.is_empty() && lines.len() < 16 {
            lines.push(current);
        }
        if lines.len() >= 16 {
            break;
        }
    }
    if lines.is_empty() {
        lines.push("No additional description available.".into());
    }
    lines
}
