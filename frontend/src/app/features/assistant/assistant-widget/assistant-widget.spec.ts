import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AssistantChat, AssistantStatus, ChatMessage } from '../assistant-chat';
import { AssistantWidget } from './assistant-widget';

describe('AssistantWidget', () => {
  let fixture: ComponentFixture<AssistantWidget>;
  let root: HTMLElement;
  let fake: {
    status: ReturnType<typeof signal<AssistantStatus | null>>;
    messages: ReturnType<typeof signal<ChatMessage[]>>;
    answering: ReturnType<typeof signal<boolean>>;
    ready: () => boolean;
    refreshStatus: ReturnType<typeof vi.fn>;
    ask: ReturnType<typeof vi.fn>;
    cancel: ReturnType<typeof vi.fn>;
    reset: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    const status = signal<AssistantStatus | null>({ state: 'READY' });
    fake = {
      status,
      messages: signal<ChatMessage[]>([]),
      answering: signal(false),
      ready: () => status()?.state === 'READY',
      refreshStatus: vi.fn(),
      ask: vi.fn(() => Promise.resolve()),
      cancel: vi.fn(),
      reset: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [AssistantWidget],
      providers: [{ provide: AssistantChat, useValue: fake }],
    }).compileComponents();
    fixture = TestBed.createComponent(AssistantWidget);
    root = fixture.nativeElement as HTMLElement;
    document.body.appendChild(root);
    fixture.detectChanges();
  });

  afterEach(() => root.remove());

  const toggle = () => root.querySelector<HTMLButtonElement>('button.toggle')!;
  const panel = () => root.querySelector('#assistant-panel');
  const textarea = () => root.querySelector<HTMLTextAreaElement>('textarea')!;
  const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
  const open = async () => {
    toggle().click();
    fixture.detectChanges();
    await flush();
  };
  const type = (text: string) => {
    textarea().value = text;
    textarea().dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };

  it('checks the assistant when it appears and says on the icon whether it is available', () => {
    expect(fake.refreshStatus).toHaveBeenCalledTimes(1);
    expect(toggle().getAttribute('aria-label')).toContain('Asistente disponible');
    expect(toggle().classList).toContain('ready');

    fake.status.set({ state: 'UNAVAILABLE' });
    fixture.detectChanges();
    expect(toggle().getAttribute('aria-label')).toContain('Asistente no disponible');
    expect(toggle().classList).not.toContain('ready');
  });

  describe('opening and closing', () => {
    it('opens the panel over the current screen and focuses the question', async () => {
      expect(panel()).toBeNull();

      await open();

      expect(panel()).not.toBeNull();
      expect(panel()!.getAttribute('role')).toBe('dialog');
      expect(toggle().getAttribute('aria-expanded')).toBe('true');
      expect(document.activeElement).toBe(textarea());
      expect(fake.refreshStatus).toHaveBeenCalledTimes(2);
    });

    it('closes with the same button and gives the focus back to it', async () => {
      await open();

      toggle().click();
      fixture.detectChanges();

      expect(panel()).toBeNull();
      expect(document.activeElement).toBe(toggle());
    });

    it('closes with Escape and with the close button', async () => {
      await open();
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
      fixture.detectChanges();
      expect(panel()).toBeNull();

      await open();
      root.querySelector<HTMLButtonElement>('button.close')!.click();
      fixture.detectChanges();
      expect(panel()).toBeNull();
    });
  });

  describe('asking', () => {
    beforeEach(open);

    it('sends with the button and empties the field', () => {
      type('¿Qué es un binding?');

      root.querySelector('form')!.dispatchEvent(new Event('submit'));
      fixture.detectChanges();

      expect(fake.ask).toHaveBeenCalledWith('¿Qué es un binding?');
      expect(textarea().value).toBe('');
    });

    it('sends with Enter and makes a new line with Shift+Enter', () => {
      type('Hola');
      textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', shiftKey: true }));
      expect(fake.ask).not.toHaveBeenCalled();

      textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
      expect(fake.ask).toHaveBeenCalledWith('Hola');
    });

    it('does not send an empty question', () => {
      type('   ');
      textarea().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

      expect(fake.ask).not.toHaveBeenCalled();
      expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    });

    it('limits the question to 500 characters', () => {
      expect(textarea().getAttribute('maxlength')).toBe('500');
    });

    it('offers Cancel while answering', () => {
      fake.answering.set(true);
      fixture.detectChanges();

      const cancel = Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Cancelar')!;
      cancel.click();

      expect(fake.cancel).toHaveBeenCalled();
    });

    it('starts a new conversation', () => {
      fake.messages.set([{ role: 'USER', text: 'Hola' }]);
      fixture.detectChanges();

      Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Nueva conversación')!.click();

      expect(fake.reset).toHaveBeenCalled();
    });
  });

  describe('the conversation', () => {
    beforeEach(open);

    it('starts with a hint that the assistant only reads', () => {
      expect(root.querySelector('.hint')?.textContent).toContain('Solo leo');
    });

    it('shows the turns, the one being written, the cancelled and the failed', () => {
      fake.messages.set([
        { role: 'USER', text: 'Hola' },
        { role: 'ASSISTANT', text: 'Todo bien.' },
        { role: 'USER', text: '¿Y eso?' },
        { role: 'ASSISTANT', text: '' },
      ]);
      fixture.detectChanges();
      expect(root.querySelectorAll('.message.user')).toHaveLength(2);
      expect(root.querySelector('.typing')?.textContent).toContain('Pensando');

      fake.messages.set([
        { role: 'USER', text: 'Hola' },
        { role: 'ASSISTANT', text: 'Med', cancelled: true },
        { role: 'USER', text: 'Otra' },
        { role: 'ASSISTANT', text: '', error: 'El modelo ha fallado.' },
      ]);
      fixture.detectChanges();
      expect(root.textContent).toContain('Respuesta cancelada.');
      expect(root.querySelector('.meta.error')?.textContent).toContain('El modelo ha fallado.');
    });

    it('shows the answer as text, never as HTML', () => {
      fake.messages.set([{ role: 'ASSISTANT', text: '<img src=x onerror=alert(1)><b>negrita</b>' }]);
      fixture.detectChanges();

      const text = root.querySelector('.message .text')!;
      expect(text.textContent).toBe('<img src=x onerror=alert(1)><b>negrita</b>');
      expect(text.querySelector('img, b')).toBeNull();
    });

    it('shows the bold, italics and code the model writes, without adding spaces', () => {
      fake.messages.set([{ role: 'ASSISTANT', text: '7. **Acciones**: usa `trace_on`, *solo* en local.\n- **<b>x</b>**' }]);
      fixture.detectChanges();

      const text = root.querySelector('.message .text')!;
      expect(text.textContent).toBe('7. Acciones: usa trace_on, solo en local.\n- <b>x</b>');
      expect(Array.from(text.querySelectorAll('strong')).map((e) => e.textContent)).toEqual(['Acciones', '<b>x</b>']);
      expect(text.querySelector('code')?.textContent).toBe('trace_on');
      expect(text.querySelector('em')?.textContent).toBe('solo');
      expect(text.querySelector('b')).toBeNull();
    });

    it('announces new answers politely', () => {
      expect(root.querySelector('.messages')?.getAttribute('aria-live')).toBe('polite');
    });
  });

  describe('when the model is not ready', () => {
    it('explains why and blocks the question while it prepares', async () => {
      fake.status.set({ state: 'PREPARING', message: 'Se está descargando el modelo (unos 2 GB).' });
      await open();

      expect(root.querySelector('.notice')?.textContent).toContain('Se está descargando el modelo');
      expect(textarea().disabled).toBe(true);
    });

    it('explains why when it is unavailable', async () => {
      fake.status.set({ state: 'UNAVAILABLE', message: 'Arrancaste sin el modelo.' });
      await open();

      expect(root.querySelector('.notice')?.textContent).toContain('Arrancaste sin el modelo.');
      expect(textarea().disabled).toBe(true);
    });

    it('says it is checking while the state is unknown', async () => {
      fake.status.set(null);
      await open();

      expect(root.querySelector('.notice')?.textContent).toContain('Comprobando el asistente');
    });
  });
});
