import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AssistantChat, AssistantStatus } from '../../assistant/assistant-chat';
import { AssistantStatusCard } from './assistant-status';

describe('AssistantStatusCard', () => {
  let fixture: ComponentFixture<AssistantStatusCard>;
  let root: HTMLElement;
  const status = signal<AssistantStatus | null>(null);
  const refreshStatus = vi.fn();

  beforeEach(async () => {
    status.set(null);
    refreshStatus.mockClear();
    await TestBed.configureTestingModule({
      imports: [AssistantStatusCard],
      providers: [{ provide: AssistantChat, useValue: { status, refreshStatus } }],
    }).compileComponents();
    fixture = TestBed.createComponent(AssistantStatusCard);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const value = () => root.querySelector('dd')?.textContent?.trim();
  const set = (next: AssistantStatus) => {
    status.set(next);
    fixture.detectChanges();
  };

  it('is a card of its own, apart from RabbitMQ', () => {
    expect(root.querySelector('h2')?.textContent).toBe('Asistente de IA');
    expect(root.textContent).not.toContain('RabbitMQ');
  });

  it('checks the model when it opens and says so meanwhile', () => {
    expect(refreshStatus).toHaveBeenCalledTimes(1);
    expect(value()).toBe('Comprobando…');
  });

  it('says when the model is ready and where to ask', () => {
    set({ state: 'READY' });

    expect(value()).toBe('Listo');
    expect(root.textContent).toContain('icono de chat de la cabecera');
  });

  it('says it is being prepared, with the reason', () => {
    set({ state: 'PREPARING', message: 'Se está descargando el modelo (unos 2 GB).' });

    expect(value()).toBe('Preparándose');
    expect(root.querySelector('.model-message')?.textContent).toContain('Se está descargando el modelo');
  });

  it('says it is unavailable, with the reason', () => {
    set({ state: 'UNAVAILABLE', message: 'Arrancaste sin el modelo (OLLAMA_REPLICAS=0).' });

    expect(value()).toBe('No disponible');
    expect(root.querySelector('.model-message')?.textContent).toContain('OLLAMA_REPLICAS=0');
  });

  it('checks again with the button', () => {
    root.querySelector('button')!.click();

    expect(refreshStatus).toHaveBeenCalledTimes(2);
  });
});
