import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { installDialogPolyfill, pressEscape } from '../../testing/dialog-polyfill';
import { ConfirmDialog, ConfirmRequest } from './confirm-dialog';

@Component({
  imports: [ConfirmDialog],
  template: '<app-confirm-dialog />',
})
class Host {
  readonly dialog = viewChild.required(ConfirmDialog);
}

describe('ConfirmDialog', () => {
  const request: ConfirmRequest = {
    title: 'Eliminar la cola «pedidos»',
    message: 'Se perderán sus mensajes y sus bindings.',
  };

  let fixture: ComponentFixture<Host>;
  let page: HTMLElement;

  beforeAll(() => installDialogPolyfill());

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    page = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  const dialogElement = () => page.querySelector('dialog') as HTMLDialogElement;
  const buttons = () => Array.from(page.querySelectorAll<HTMLButtonElement>('dialog button'));
  const buttonLabelled = (label: string) =>
    buttons().find((button) => button.textContent?.trim() === label) as HTMLButtonElement;

  /** Abre el diálogo; `answer` se resuelve cuando el usuario responde (no hay que esperarlo aquí). */
  async function open(value: ConfirmRequest = request): Promise<{ answer: Promise<boolean> }> {
    const answer = fixture.componentInstance.dialog().open(value);
    fixture.detectChanges();
    await fixture.whenStable();
    return { answer };
  }

  it('is closed until it is opened', () => {
    expect(dialogElement().hasAttribute('open')).toBe(false);
  });

  it('opens showing the title, the warning and the buttons', async () => {
    await open();

    expect(dialogElement().hasAttribute('open')).toBe(true);
    expect(page.querySelector('h2')?.textContent).toBe('Eliminar la cola «pedidos»');
    expect(page.querySelector('dialog p')?.textContent).toBe('Se perderán sus mensajes y sus bindings.');
    expect(buttons().map((button) => button.textContent?.trim())).toEqual(['Cancelar', 'Confirmar']);
  });

  it('is labelled by its title and described by its warning', async () => {
    await open();

    const dialog = dialogElement();
    expect(page.querySelector(`#${dialog.getAttribute('aria-labelledby')}`)?.textContent).toContain('Eliminar');
    expect(page.querySelector(`#${dialog.getAttribute('aria-describedby')}`)?.textContent).toContain(
      'Se perderán',
    );
  });

  it('lists the affected items when there are some', async () => {
    await open({ ...request, items: ['cola-a', 'cola-b'] });

    const items = Array.from(page.querySelectorAll('dialog li')).map((item) => item.textContent);
    expect(items).toEqual(['cola-a', 'cola-b']);
  });

  it('shows no list when there are no affected items', async () => {
    await open({ ...request, items: [] });

    expect(page.querySelector('dialog ul')).toBeNull();
  });

  it('uses the given labels for the buttons', async () => {
    await open({ ...request, confirmLabel: 'Eliminar', cancelLabel: 'Conservar' });

    expect(buttons().map((button) => button.textContent?.trim())).toEqual(['Conservar', 'Eliminar']);
  });

  it('resolves true and closes when confirming', async () => {
    const { answer } = await open();

    buttonLabelled('Confirmar').click();

    expect(await answer).toBe(true);
    expect(dialogElement().hasAttribute('open')).toBe(false);
  });

  it('resolves false and closes when cancelling', async () => {
    const { answer } = await open();

    buttonLabelled('Cancelar').click();

    expect(await answer).toBe(false);
    expect(dialogElement().hasAttribute('open')).toBe(false);
  });

  it('resolves false and closes with Escape', async () => {
    const { answer } = await open();

    pressEscape(dialogElement());

    expect(await answer).toBe(false);
    expect(dialogElement().hasAttribute('open')).toBe(false);
  });

  it('puts the initial focus on the safe option (cancel)', async () => {
    await open();

    expect(buttonLabelled('Cancelar').hasAttribute('autofocus')).toBe(true);
    expect(buttonLabelled('Confirmar').hasAttribute('autofocus')).toBe(false);
  });

  it('uses native buttons, so Enter and Space work and they are never disabled', async () => {
    await open();

    buttons().forEach((button) => {
      expect(button.tagName).toBe('BUTTON');
      expect(button.type).toBe('button');
      expect(button.disabled).toBe(false);
    });
  });

  it('can be opened again with other content after being closed', async () => {
    const first = await open();
    buttonLabelled('Cancelar').click();
    await first.answer;

    const second = await open({ title: 'Otra', message: 'Otro aviso.' });
    buttonLabelled('Confirmar').click();

    expect(page.querySelector('h2')?.textContent).toBe('Otra');
    expect(await second.answer).toBe(true);
  });

  it('counts opening it again without answering as not confirmed', async () => {
    const first = await open();

    await open({ title: 'Otra', message: 'Otro aviso.' });

    expect(await first.answer).toBe(false);
  });
});
