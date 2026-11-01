import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AssistantWidget } from '../../features/assistant/assistant-widget/assistant-widget';
import { NAV_SECTIONS, NavSection } from '../nav-sections';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, AssistantWidget],
  templateUrl: './shell.html',
  styleUrl: './shell.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Shell {
  protected readonly sections = NAV_SECTIONS;
  protected readonly notice = signal<string | null>(null);

  /** Las secciones deshabilitadas no navegan: solo avisan de que llegarán más adelante. */
  protected showComingSoon(section: NavSection): void {
    this.notice.set(`${section.label}: Disponible próximamente`);
  }
}
