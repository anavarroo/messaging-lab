import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnInit,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { AssistantChat, MAX_QUESTION } from '../assistant-chat';
import { formatSegments } from './formatted-text';

/**
 * Botón del asistente en la cabecera y su panel de chat. El panel se abre sobre la pantalla actual, sin cambiar de
 * pantalla, y se cierra con el mismo botón o con Escape.
 */
@Component({
  selector: 'app-assistant-widget',
  templateUrl: './assistant-widget.html',
  styleUrl: './assistant-widget.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:keydown.escape)': 'close()' },
})
export class AssistantWidget implements OnInit {
  protected readonly chat = inject(AssistantChat);
  protected readonly open = signal(false);
  protected readonly question = signal('');
  protected readonly max = MAX_QUESTION;
  /** El formato básico (negrita, cursiva, código) que escribe el modelo, sin HTML. */
  protected readonly format = formatSegments;

  private readonly toggleButton = viewChild.required<ElementRef<HTMLButtonElement>>('toggleButton');
  private readonly input = viewChild<ElementRef<HTMLTextAreaElement>>('input');
  private readonly list = viewChild<ElementRef<HTMLElement>>('list');

  constructor() {
    // la conversación se mantiene a la vista: baja al último mensaje cuando llega algo nuevo
    effect(() => {
      this.chat.messages();
      queueMicrotask(() => {
        const list = this.list()?.nativeElement;
        if (list) {
          list.scrollTop = list.scrollHeight;
        }
      });
    });
  }

  ngOnInit(): void {
    this.chat.refreshStatus();
  }

  protected toggle(): void {
    if (this.open()) {
      this.close();
    } else {
      this.open.set(true);
      this.chat.refreshStatus();
      queueMicrotask(() => this.input()?.nativeElement.focus());
    }
  }

  close(): void {
    if (this.open()) {
      this.open.set(false);
      this.toggleButton().nativeElement.focus();
    }
  }

  protected send(): void {
    const text = this.question().trim();
    if (!text || !this.chat.ready() || this.chat.answering()) {
      return;
    }
    this.question.set('');
    void this.chat.ask(text);
  }

  /** Intro envía; Mayúsculas + Intro hace un salto de línea. */
  protected onKey(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.send();
    }
  }

  protected statusLabel(): string {
    switch (this.chat.status()?.state) {
      case 'READY':
        return 'Asistente disponible';
      case 'PREPARING':
        return 'El asistente se está preparando';
      case 'UNAVAILABLE':
        return 'Asistente no disponible';
      default:
        return 'Comprobando el asistente';
    }
  }
}
