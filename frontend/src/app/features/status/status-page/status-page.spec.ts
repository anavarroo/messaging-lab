import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { BackendStatus } from '../../../core/status/backend-status';
import { StatusApiService, StatusError } from '../../../core/status/status-api.service';
import { StatusPage } from './status-page';

describe('StatusPage', () => {
  const valid: BackendStatus = {
    status: 'AVAILABLE',
    name: 'messaging-lab-backend',
    version: '0.1.0',
  };

  let fixture: ComponentFixture<StatusPage>;
  let page: HTMLElement;
  /** Una respuesta controlable por cada consulta lanzada al servicio. */
  let responses: Subject<BackendStatus>[];
  let getStatus: ReturnType<typeof vi.fn>;

  beforeEach(async () => {
    responses = [];
    getStatus = vi.fn(() => {
      const response = new Subject<BackendStatus>();
      responses.push(response);
      return response;
    });
    await TestBed.configureTestingModule({
      imports: [StatusPage],
      providers: [{ provide: StatusApiService, useValue: { getStatus } }],
    }).compileComponents();

    fixture = TestBed.createComponent(StatusPage);
    page = fixture.nativeElement as HTMLElement;
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function failWith(kind: StatusError['kind'], index = responses.length - 1) {
    responses[index].error(new StatusError(kind));
    await settle();
  }

  async function succeed(index = responses.length - 1) {
    responses[index].next(valid);
    await settle();
  }

  const retryButton = () =>
    Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes('Reintentar'));
  const alert = () => page.querySelector('[role="alert"]');

  it('queries the status when the page opens', () => {
    expect(getStatus).toHaveBeenCalledTimes(1);
  });

  it('shows a loading indicator while the query is in progress', () => {
    expect(page.textContent).toContain('Consultando el estado del backend');
    expect(page.querySelector('.details')).toBeNull();
    expect(alert()).toBeNull();
    expect(retryButton()).toBeUndefined();
  });

  it('shows the status, name and version when the backend responds', async () => {
    await succeed();

    expect(page.textContent).toContain('Disponible');
    expect(page.textContent).toContain('messaging-lab-backend');
    expect(page.textContent).toContain('0.1.0');
    expect(page.textContent).not.toContain('Consultando');
    expect(alert()).toBeNull();
    expect(retryButton()).toBeUndefined();
  });

  it('shows the unavailable message and a retry button when the backend is down', async () => {
    await failWith('unavailable');

    expect(alert()?.textContent).toContain('El backend no está disponible.');
    expect(retryButton()).toBeDefined();
    expect(retryButton()?.disabled).toBe(false);
  });

  it('shows the invalid response message when the backend fails', async () => {
    await failWith('invalid');

    expect(alert()?.textContent).toContain('No se ha podido obtener el estado del backend.');
    expect(retryButton()).toBeDefined();
  });

  it('shows the generic message when an unexpected error occurs', async () => {
    responses[0].error(new Error('boom'));
    await settle();

    expect(alert()?.textContent).toContain('No se ha podido obtener el estado del backend.');
  });

  it('replaces the error with the status when a retry succeeds', async () => {
    await failWith('unavailable');

    retryButton()!.click();
    await settle();
    await succeed();

    expect(getStatus).toHaveBeenCalledTimes(2);
    expect(alert()).toBeNull();
    expect(page.textContent).toContain('Disponible');
    expect(page.textContent).toContain('0.1.0');
    expect(retryButton()).toBeUndefined();
  });

  it('keeps showing the error when a retry fails again', async () => {
    await failWith('unavailable');

    retryButton()!.click();
    await settle();
    await failWith('invalid');

    expect(alert()?.textContent).toContain('No se ha podido obtener el estado del backend.');
    expect(retryButton()?.disabled).toBe(false);
  });

  it('disables the retry button while a retry is in progress', async () => {
    await failWith('unavailable');

    retryButton()!.click();
    await settle();

    expect(retryButton()?.disabled).toBe(true);
    expect(page.textContent).toContain('Consultando el estado del backend');

    retryButton()!.click();
    await settle();
    expect(getStatus).toHaveBeenCalledTimes(2);
  });

  it('announces status changes to assistive technologies', () => {
    expect(page.querySelector('[aria-live="polite"]')).not.toBeNull();
  });
});
