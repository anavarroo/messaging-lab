import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Shell } from './shell';

@Component({ template: '<p>Contenido de estado</p>' })
class StatusStub {}

describe('Shell', () => {
  const disabledSections = [
    {
      label: 'Laboratorio RabbitMQ',
      description:
        'Colas, exchanges, bindings, routing keys, publicación, acknowledgements y dead-letter queues.',
    },
    {
      label: 'Laboratorio Kafka',
      description: 'Topics, particiones, offsets, productores, consumer groups y lag.',
    },
    {
      label: 'Visualizador de flujo',
      description:
        'Publicar un mensaje y seguir visualmente su recorrido, las entregas y los errores.',
    },
  ];

  let fixture: ComponentFixture<Shell>;
  let page: HTMLElement;
  let router: Router;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [provideRouter([{ path: 'status', component: StatusStub }])],
    }).compileComponents();

    router = TestBed.inject(Router);
    fixture = TestBed.createComponent(Shell);
    page = fixture.nativeElement as HTMLElement;
    await router.navigateByUrl('/status');
    await settle();
  });

  async function settle() {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  const disabledButtons = () =>
    Array.from(page.querySelectorAll<HTMLButtonElement>('button[aria-disabled="true"]'));
  const notice = () => page.querySelector('[role="status"]')?.textContent?.trim();

  it('shows the lab name in the header', () => {
    expect(page.querySelector('header h1')?.textContent).toContain('messaging-lab');
  });

  it('renders the router content', () => {
    expect(page.querySelector('main')?.textContent).toContain('Contenido de estado');
  });

  it('shows the status section as an active link', () => {
    const link = page.querySelector<HTMLAnchorElement>('nav a');

    expect(link?.textContent).toContain('Estado');
    expect(link?.getAttribute('href')).toBe('/status');
    expect(link?.classList).toContain('active');
    expect(link?.getAttribute('aria-current')).toBe('page');
  });

  it('shows the three future sections disabled with their description', () => {
    const buttons = disabledButtons();

    expect(buttons).toHaveLength(disabledSections.length);
    disabledSections.forEach((section, index) => {
      expect(buttons[index].textContent).toContain(section.label);
      expect(buttons[index].textContent).toContain(section.description);
    });
  });

  it('does not show a notice until a disabled section is activated', () => {
    expect(notice()).toBe('');
  });

  it.each(disabledSections.map((section, index) => [section.label, index] as const))(
    'shows "Disponible próximamente" without navigating when activating %s',
    async (label, index) => {
      const urlBefore = router.url;

      disabledButtons()[index].click();
      await settle();

      expect(notice()).toContain('Disponible próximamente');
      expect(notice()).toContain(label);
      expect(router.url).toBe(urlBefore);
      expect(router.url).toBe('/status');
    },
  );

  it('keeps the disabled sections focusable so they can be activated with the keyboard', () => {
    disabledButtons().forEach((button) => {
      expect(button.tagName).toBe('BUTTON');
      expect(button.disabled).toBe(false);
    });
  });
});
