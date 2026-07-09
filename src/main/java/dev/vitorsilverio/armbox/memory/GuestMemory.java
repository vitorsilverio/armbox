package dev.vitorsilverio.armbox.memory;

import dev.vitorsilverio.armjitter.memory.AddressSpace;
import dev.vitorsilverio.armjitter.memory.MemoryAccessType;

/// Espaço de endereçamento flat de 32 bits do processo guest, paginado em 4 KB.
///
/// Diferente de um barramento de console, não há espelhamento nem open bus: acesso a
/// página não mapeada é um erro do guest e lança [GuestSegmentationFault] — o
/// equivalente user-mode de um SIGSEGV.
///
/// Endereços são tratados como unsigned de 32 bits (o índice de página usa `>>>`).
public final class GuestMemory implements AddressSpace {
    /// Tamanho de página do Linux ARM de 32 bits (também reportado em `AT_PAGESZ`).
    public static final int PAGE_SHIFT = 12;
    public static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final int PAGE_COUNT = 1 << (32 - PAGE_SHIFT);
    private static final int OFFSET_MASK = PAGE_SIZE - 1;

    private final byte[][] pages = new byte[PAGE_COUNT][];

    /// Mapeia (aloca zerado) o intervalo `[address, address+sizeBytes)`, arredondando
    /// para fronteiras de página. Páginas já mapeadas são preservadas.
    public void map(int address, int sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be > 0: " + sizeBytes);
        }
        int first = address >>> PAGE_SHIFT;
        int last = (int) ((Integer.toUnsignedLong(address) + sizeBytes - 1) >>> PAGE_SHIFT);
        for (int page = first; page <= last; page++) {
            if (pages[page] == null) {
                pages[page] = new byte[PAGE_SIZE];
            }
        }
    }

    /// Desmapeia o intervalo (páginas inteiras contidas nele). Acesso posterior falha.
    public void unmap(int address, int sizeBytes) {
        int first = address >>> PAGE_SHIFT;
        int last = (int) ((Integer.toUnsignedLong(address) + sizeBytes - 1) >>> PAGE_SHIFT);
        for (int page = first; page <= last; page++) {
            pages[page] = null;
        }
    }

    /// Retorna `true` se a página do endereço está mapeada.
    public boolean isMapped(int address) {
        return pages[address >>> PAGE_SHIFT] != null;
    }

    private byte[] page(int address, String access) {
        byte[] page = pages[address >>> PAGE_SHIFT];
        if (page == null) {
            throw new GuestSegmentationFault(address, access);
        }
        return page;
    }

    @Override
    public int read8(int address) {
        return page(address, "read8")[address & OFFSET_MASK] & 0xFF;
    }

    @Override
    public int read16(int address) {
        int offset = address & OFFSET_MASK;
        if (offset <= PAGE_SIZE - 2) {
            byte[] page = page(address, "read16");
            return (page[offset] & 0xFF) | ((page[offset + 1] & 0xFF) << 8);
        }
        return read8(address) | (read8(address + 1) << 8);
    }

    @Override
    public int read32(int address) {
        int offset = address & OFFSET_MASK;
        if (offset <= PAGE_SIZE - 4) {
            byte[] page = page(address, "read32");
            return (page[offset] & 0xFF)
                    | ((page[offset + 1] & 0xFF) << 8)
                    | ((page[offset + 2] & 0xFF) << 16)
                    | ((page[offset + 3] & 0xFF) << 24);
        }
        return read16(address) | (read16(address + 2) << 16);
    }

    @Override
    public void write8(int address, int value) {
        page(address, "write8")[address & OFFSET_MASK] = (byte) value;
    }

    @Override
    public void write16(int address, int value) {
        int offset = address & OFFSET_MASK;
        if (offset <= PAGE_SIZE - 2) {
            byte[] page = page(address, "write16");
            page[offset] = (byte) value;
            page[offset + 1] = (byte) (value >>> 8);
            return;
        }
        write8(address, value);
        write8(address + 1, value >>> 8);
    }

    @Override
    public void write32(int address, int value) {
        int offset = address & OFFSET_MASK;
        if (offset <= PAGE_SIZE - 4) {
            byte[] page = page(address, "write32");
            page[offset] = (byte) value;
            page[offset + 1] = (byte) (value >>> 8);
            page[offset + 2] = (byte) (value >>> 16);
            page[offset + 3] = (byte) (value >>> 24);
            return;
        }
        write16(address, value);
        write16(address + 2, value >>> 16);
    }

    /// Copia `length` bytes da memória guest a partir de `address`.
    public byte[] readBytes(int address, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) read8(address + i);
        }
        return out;
    }

    /// Escreve `data` na memória guest a partir de `address` (páginas devem estar mapeadas).
    public void writeBytes(int address, byte[] data, int offset, int length) {
        for (int i = 0; i < length; i++) {
            write8(address + i, data[offset + i]);
        }
    }

    /// Lê uma string C (terminada em NUL) da memória guest.
    public String readCString(int address) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ; i++) {
            int b = read8(address + i);
            if (b == 0) {
                return sb.toString();
            }
            sb.append((char) b);
        }
    }

    /// Processo user-mode não tem waitstates de barramento.
    @Override
    public boolean providesAccessCycles() {
        return false;
    }

    @Override
    public int accessCycles(int address, int sizeBytes, MemoryAccessType type) {
        return 0;
    }
}
